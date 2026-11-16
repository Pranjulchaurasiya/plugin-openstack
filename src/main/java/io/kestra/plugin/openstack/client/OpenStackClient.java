package io.kestra.plugin.openstack.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.openstack.client.model.*;
import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.io.Closeable;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Getter
public class OpenStackClient implements Closeable {

    private static final String DEFAULT_DOMAIN = "Default";
    private static final String DEFAULT_INTERFACE = "public";
    private static final String NOVA_MICROVERSION = "2.58";
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    private final RunContext runContext;
    private final HttpClient httpClient;
    private final String authUrl;
    private final String applicationCredentialId;
    private final String applicationCredentialSecret;
    private final String username;
    private final String password;
    private final String projectName;
    private final String userDomainName;
    private final String projectDomainName;
    private final String region;
    private final String endpointType;

    private String token;
    private String computeUrl;
    private String imageUrl;

    @Builder
    public OpenStackClient(
        RunContext runContext,
        HttpConfiguration httpConfiguration,
        String authUrl,
        String applicationCredentialId,
        String applicationCredentialSecret,
        String username,
        String password,
        String projectName,
        String userDomainName,
        String projectDomainName,
        String region,
        String endpointType
    ) throws Exception {
        this.runContext = runContext;
        this.httpClient = new HttpClient(runContext, httpConfiguration);
        this.authUrl = cleanUrl(authUrl);
        this.applicationCredentialId = applicationCredentialId;
        this.applicationCredentialSecret = applicationCredentialSecret;
        this.username = username;
        this.password = password;
        this.projectName = projectName;
        this.userDomainName = StringUtils.isNotBlank(userDomainName) ? userDomainName : DEFAULT_DOMAIN;
        this.projectDomainName = StringUtils.isNotBlank(projectDomainName) ? projectDomainName : DEFAULT_DOMAIN;
        this.region = region;
        this.endpointType = StringUtils.isNotBlank(endpointType) ? endpointType : DEFAULT_INTERFACE;
    }

    private static String cleanUrl(String url) {
        if (url == null) {
            return null;
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public synchronized void authenticate() throws Exception {
        if (this.token != null) {
            return;
        }

        KeystoneAuthRequest.Identity.IdentityBuilder identityBuilder = KeystoneAuthRequest.Identity.builder();
        KeystoneAuthRequest.Scope scope = null;

        if (StringUtils.isNotBlank(applicationCredentialId) && StringUtils.isNotBlank(applicationCredentialSecret)) {
            identityBuilder
                .methods(List.of("application_credential"))
                .applicationCredential(KeystoneAuthRequest.ApplicationCredential.builder()
                    .id(applicationCredentialId)
                    .secret(applicationCredentialSecret)
                    .build());
        } else if (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)) {
            identityBuilder
                .methods(List.of("password"))
                .password(KeystoneAuthRequest.Password.builder()
                    .user(KeystoneAuthRequest.User.builder()
                        .name(username)
                        .password(password)
                        .domain(KeystoneAuthRequest.Domain.builder().name(userDomainName).build())
                        .build())
                    .build());

            if (StringUtils.isNotBlank(projectName)) {
                scope = KeystoneAuthRequest.Scope.builder()
                    .project(KeystoneAuthRequest.Project.builder()
                        .name(projectName)
                        .domain(KeystoneAuthRequest.Domain.builder().name(projectDomainName).build())
                        .build())
                    .build();
            }
        } else {
            throw new IllegalArgumentException(
                "Missing credentials: must supply either applicationCredentialId/Secret or username/password."
            );
        }

        KeystoneAuthRequest authRequest = KeystoneAuthRequest.builder()
            .auth(KeystoneAuthRequest.Auth.builder()
                .identity(identityBuilder.build())
                .scope(scope)
                .build())
            .build();

        String tokenEndpoint = this.authUrl.endsWith("/v3")
            ? this.authUrl + "/auth/tokens"
            : (this.authUrl.endsWith("/auth/tokens") ? this.authUrl : this.authUrl + "/v3/auth/tokens");

        String requestBody = MAPPER.writeValueAsString(authRequest);

        HttpRequest req = HttpRequest.builder()
            .method("POST")
            .uri(URI.create(tokenEndpoint))
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .body(HttpRequest.StringRequestBody.builder()
                .content(requestBody)
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .build())
            .build();

        HttpResponse<String> resp = executeHttp(req, String.class);

        this.token = resp.getHeaders().firstValue("X-Subject-Token")
            .orElseThrow(() -> new IllegalStateException("Keystone response did not contain X-Subject-Token header"));

        KeystoneTokenResponse tokenResponse = MAPPER.readValue(resp.getBody(), KeystoneTokenResponse.class);
        resolveServiceEndpoints(tokenResponse);
    }

    private void resolveServiceEndpoints(KeystoneTokenResponse tokenResponse) {
        if (tokenResponse == null || tokenResponse.getToken() == null || tokenResponse.getToken().getCatalog() == null) {
            throw new IllegalStateException("Keystone token response contains empty service catalog.");
        }

        List<KeystoneTokenResponse.ServiceCatalogEntry> catalog = tokenResponse.getToken().getCatalog();

        for (KeystoneTokenResponse.ServiceCatalogEntry entry : catalog) {
            if ("compute".equalsIgnoreCase(entry.getType()) && entry.getEndpoints() != null) {
                for (KeystoneTokenResponse.ServiceEndpoint ep : entry.getEndpoints()) {
                    if (matchesEndpoint(ep)) {
                        this.computeUrl = cleanUrl(ep.getUrl());
                        break;
                    }
                }
            } else if ("image".equalsIgnoreCase(entry.getType()) && entry.getEndpoints() != null) {
                for (KeystoneTokenResponse.ServiceEndpoint ep : entry.getEndpoints()) {
                    if (matchesEndpoint(ep)) {
                        this.imageUrl = cleanUrl(ep.getUrl());
                        break;
                    }
                }
            }
        }

        if (this.computeUrl == null) {
            throw new IllegalStateException("Could not resolve compute endpoint URL from Keystone catalog for region " + region);
        }
    }

    private boolean matchesEndpoint(KeystoneTokenResponse.ServiceEndpoint ep) {
        if (ep.getUrl() == null) {
            return false;
        }
        boolean interfaceMatch = this.endpointType.equalsIgnoreCase(ep.getInterfaceType());
        if (!interfaceMatch) {
            return false;
        }
        if (StringUtils.isNotBlank(this.region)) {
            return this.region.equalsIgnoreCase(ep.getRegion()) || this.region.equalsIgnoreCase(ep.getRegionId());
        }
        return true;
    }

    public Server createServer(ServerPayloads.Create payload) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers";
        String body = MAPPER.writeValueAsString(payload);

        HttpRequest req = computeRequest("POST", uri, body);
        HttpResponse<String> resp = executeHttp(req, String.class);
        ServerResponses.SingleServer single = MAPPER.readValue(resp.getBody(), ServerResponses.SingleServer.class);
        return single.getServer();
    }

    public Server getServer(String serverId) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers/" + serverId;

        HttpRequest req = computeRequest("GET", uri, null);
        HttpResponse<String> resp = executeHttp(req, String.class);
        ServerResponses.SingleServer single = MAPPER.readValue(resp.getBody(), ServerResponses.SingleServer.class);
        return single.getServer();
    }

    public List<Server> listServers(Map<String, String> queryParams) throws Exception {
        authenticate();
        StringBuilder uriBuilder = new StringBuilder(this.computeUrl).append("/servers/detail");
        if (queryParams != null && !queryParams.isEmpty()) {
            uriBuilder.append("?");
            boolean first = true;
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                if (entry.getValue() != null) {
                    if (!first) {
                        uriBuilder.append("&");
                    }
                    first = false;
                    uriBuilder.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8))
                        .append("=")
                        .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                }
            }
        }

        HttpRequest req = computeRequest("GET", uriBuilder.toString(), null);
        HttpResponse<String> resp = executeHttp(req, String.class);
        ServerResponses.MultipleServers multiple = MAPPER.readValue(resp.getBody(), ServerResponses.MultipleServers.class);
        return multiple != null && multiple.getServers() != null ? multiple.getServers() : Collections.emptyList();
    }

    public Server updateServer(String serverId, ServerPayloads.Update payload) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers/" + serverId;
        String body = MAPPER.writeValueAsString(payload);

        HttpRequest req = computeRequest("PUT", uri, body);
        HttpResponse<String> resp = executeHttp(req, String.class);
        ServerResponses.SingleServer single = MAPPER.readValue(resp.getBody(), ServerResponses.SingleServer.class);
        return single.getServer();
    }

    public void deleteServer(String serverId) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers/" + serverId;
        HttpRequest req = computeRequest("DELETE", uri, null);
        executeHttp(req, String.class);
    }

    public String serverAction(String serverId, String actionJson) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers/" + serverId + "/action";
        HttpRequest req = computeRequest("POST", uri, actionJson);
        HttpResponse<String> resp = executeHttp(req, String.class);
        return resp.getBody();
    }

    public void startServer(String serverId) throws Exception {
        serverAction(serverId, "{\"os-start\": null}");
    }

    public void stopServer(String serverId) throws Exception {
        serverAction(serverId, "{\"os-stop\": null}");
    }

    public void rebootServer(String serverId, String type) throws Exception {
        String rebootType = StringUtils.isNotBlank(type) ? type.toUpperCase(Locale.ROOT) : "SOFT";
        serverAction(serverId, "{\"reboot\": {\"type\": \"" + rebootType + "\"}}");
    }

    public void suspendServer(String serverId) throws Exception {
        serverAction(serverId, "{\"suspend\": null}");
    }

    public void resumeServer(String serverId) throws Exception {
        serverAction(serverId, "{\"resume\": null}");
    }

    public String createSnapshot(String serverId, String snapshotName, Map<String, String> metadata) throws Exception {
        authenticate();
        Map<String, Object> inner = new HashMap<>();
        inner.put("name", snapshotName);
        if (metadata != null && !metadata.isEmpty()) {
            inner.put("metadata", metadata);
        }
        Map<String, Object> payload = Map.of("createImage", inner);
        String body = MAPPER.writeValueAsString(payload);

        String respJson = serverAction(serverId, body);
        if (StringUtils.isNotBlank(respJson)) {
            ServerResponses.CreateImage img = MAPPER.readValue(respJson, ServerResponses.CreateImage.class);
            return img.getImageId();
        }
        return null;
    }

    public void restoreServer(String serverId, String imageRef) throws Exception {
        serverAction(serverId, "{\"rebuild\": {\"imageRef\": \"" + imageRef + "\"}}");
    }

    public Map<String, String> getServerMetadata(String serverId) throws Exception {
        authenticate();
        String uri = this.computeUrl + "/servers/" + serverId + "/metadata";
        HttpRequest req = computeRequest("GET", uri, null);
        HttpResponse<String> resp = executeHttp(req, String.class);
        ServerResponses.Metadata meta = MAPPER.readValue(resp.getBody(), ServerResponses.Metadata.class);
        return meta != null && meta.getMetadata() != null ? meta.getMetadata() : Collections.emptyMap();
    }

    public List<GlanceResponses.GlanceImage> listServerSnapshots(String serverId) throws Exception {
        authenticate();
        if (this.imageUrl == null) {
            throw new IllegalStateException("Glance image service URL is not resolved from service catalog.");
        }
        String baseImage = this.imageUrl.endsWith("/v2") ? this.imageUrl : this.imageUrl + "/v2";
        String uri = baseImage + "/images?instance_uuid=" + URLEncoder.encode(serverId, StandardCharsets.UTF_8)
            + "&image_type=snapshot";

        HttpRequest req = HttpRequest.builder()
            .method("GET")
            .uri(URI.create(uri))
            .addHeader("X-Auth-Token", this.token)
            .addHeader("Accept", "application/json")
            .build();

        HttpResponse<String> resp = executeHttp(req, String.class);
        GlanceResponses.MultipleImages res = MAPPER.readValue(resp.getBody(), GlanceResponses.MultipleImages.class);
        return res != null && res.getImages() != null ? res.getImages() : Collections.emptyList();
    }

    public void deleteSnapshot(String imageId) throws Exception {
        authenticate();
        if (this.imageUrl == null) {
            throw new IllegalStateException("Glance image service URL is not resolved from service catalog.");
        }
        String baseImage = this.imageUrl.endsWith("/v2") ? this.imageUrl : this.imageUrl + "/v2";
        String uri = baseImage + "/images/" + imageId;
        HttpRequest req = HttpRequest.builder()
            .method("DELETE")
            .uri(URI.create(uri))
            .addHeader("X-Auth-Token", this.token)
            .addHeader("Accept", "application/json")
            .build();

        executeHttp(req, String.class);
    }

    private HttpRequest computeRequest(String method, String uri, String body) {
        HttpRequest.HttpRequestBuilder builder = HttpRequest.builder()
            .method(method)
            .uri(URI.create(uri))
            .addHeader("X-Auth-Token", this.token)
            .addHeader("OpenStack-API-Version", "compute " + NOVA_MICROVERSION)
            .addHeader("Accept", "application/json");

        if (body != null && !body.isBlank() && ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method))) {
            builder.addHeader("Content-Type", "application/json");
            builder.body(HttpRequest.StringRequestBody.builder()
                .content(body)
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .build());
        }

        return builder.build();
    }

    private <T> HttpResponse<T> executeHttp(HttpRequest request, Class<T> responseType) throws Exception {
        try {
            return httpClient.request(request, responseType);
        } catch (HttpClientResponseException e) {
            String errorBody = "";
            if (e.getResponse() != null && e.getResponse().getBody() != null) {
                errorBody = " | Response Body: " + e.getResponse().getBody();
            }
            throw new RuntimeException("OpenStack HTTP error: " + e.getMessage() + errorBody, e);
        }
    }

    @Override
    public void close() {
        if (this.httpClient != null) {
            try {
                this.httpClient.close();
            } catch (Exception ignored) {
            }
        }
    }
}

package io.kestra.plugin.openstack.compute;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.plugin.openstack.client.model.GlanceResponses;
import io.kestra.plugin.openstack.client.model.RebootType;
import io.kestra.plugin.openstack.client.model.Server;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@KestraTest
class OpenStackComputeTest {

    @Inject
    private RunContextFactory runContextFactory;

    private static WireMockServer wireMockServer;
    private static String authUrl;
    private static int port;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        port = wireMockServer.port();
        authUrl = "http://localhost:" + port + "/v3";
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void setupStubs() {
        wireMockServer.resetAll();

        // Stub Keystone v3 token authentication
        var tokenBody = String.format("""
            {
              "token": {
                "expires_at": "2099-01-01T00:00:00.000000Z",
                "issued_at": "2026-11-16T12:00:00.000000Z",
                "catalog": [
                  {
                    "type": "compute",
                    "name": "nova",
                    "endpoints": [
                      {
                        "interface": "public",
                        "region": "RegionOne",
                        "url": "http://localhost:%d/compute/v2.1"
                      }
                    ]
                  },
                  {
                    "type": "image",
                    "name": "glance",
                    "endpoints": [
                      {
                        "interface": "public",
                        "region": "RegionOne",
                        "url": "http://localhost:%d/image/v2"
                      }
                    ]
                  },
                  {
                    "type": "network",
                    "name": "neutron",
                    "endpoints": [
                      {
                        "interface": "public",
                        "region": "RegionOne",
                        "url": "http://localhost:%d/network/v2.0"
                      }
                    ]
                  }
                ]
              }
            }
            """, port, port, port);

        wireMockServer.stubFor(post(urlEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Subject-Token", "mock-openstack-token-xyz123")
                .withBody(tokenBody)));

        // Default stub for flavors detail
        wireMockServer.stubFor(get(urlEqualTo("/compute/v2.1/flavors/detail"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "flavors": [
                        {"id": "flavor-m1-medium-id", "name": "m1.medium", "vcpus": 2, "ram": 4096, "disk": 40},
                        {"id": "flavor-m1-large-id", "name": "m1.large", "vcpus": 4, "ram": 8192, "disk": 80}
                      ]
                    }
                    """)));

        // Default stub for Glance image resolution by name
        wireMockServer.stubFor(get(urlPathEqualTo("/image/v2/images"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "images": [
                        {"id": "img-ubuntu-2204-id", "name": "ubuntu-22.04", "status": "active"}
                      ]
                    }
                    """)));
    }

    @Test
    void testCreateServerWithAppCredentialsAndUserDataBase64() throws Exception {
        var plainUserData = "#!/bin/bash\necho 'Configuring Kestra worker node'\n";
        var expectedBase64 = Base64.getEncoder().encodeToString(plainUserData.getBytes());

        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers"))
            .withHeader("X-Auth-Token", equalTo("mock-openstack-token-xyz123"))
            .withHeader("OpenStack-API-Version", equalTo("compute 2.58"))
            .withRequestBody(matchingJsonPath("$.server.user_data", equalTo(expectedBase64)))
            .withRequestBody(matchingJsonPath("$.server.flavorRef", equalTo("flavor-m1-medium-id")))
            .withRequestBody(matchingJsonPath("$.server.imageRef", equalTo("img-ubuntu-2204-id")))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "srv-9a2f-test-01",
                        "name": "kestra-worker-01",
                        "status": "ACTIVE",
                        "adminPass": "GeneratedAdminPass123"
                      }
                    }
                    """)));

        var runContext = runContextFactory.of();

        var task = CreateServer.builder()
            .id("create_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .name(Property.ofValue("kestra-worker-01"))
            .flavorRef(Property.ofValue("m1.medium"))
            .imageRef(Property.ofValue("ubuntu-22.04"))
            .userData(Property.ofValue(plainUserData))
            .waitUntilComplete(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-9a2f-test-01"));
        assertThat(output.getStatus(), is("ACTIVE"));
        assertThat(JacksonMapper.ofJson().writeValueAsString(output), not(containsString("GeneratedAdminPass123")));
    }

    @Test
    void testCreateServerWithPasswordAuth() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "srv-9a2f-test-02",
                        "name": "kestra-worker-02",
                        "status": "ACTIVE"
                      }
                    }
                    """)));

        var runContext = runContextFactory.of();

        var task = CreateServer.builder()
            .id("create_server_pass_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .username(Property.ofValue("devuser"))
            .password(Property.ofValue("secretpassword"))
            .projectName(Property.ofValue("platform"))
            .name(Property.ofValue("kestra-worker-02"))
            .flavorRef(Property.ofValue("m1.large"))
            .waitUntilComplete(Property.ofValue(false))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-9a2f-test-02"));
        assertThat(output.getStatus(), is("ACTIVE"));
    }

    @Test
    void testListServersWithRegexAndFetchModes() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/compute/v2.1/servers/detail"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "servers": [
                        {
                          "id": "srv-01",
                          "name": "prod-app-01",
                          "status": "ACTIVE"
                        },
                        {
                          "id": "srv-02",
                          "name": "prod-db-01",
                          "status": "ACTIVE"
                        },
                        {
                          "id": "srv-03",
                          "name": "stage-app-01",
                          "status": "SHUTOFF"
                        }
                      ]
                    }
                    """)));

        var runContext = runContextFactory.of();

        // 1. FETCH mode with regex
        var fetchTask = ListServers.builder()
            .id("list_servers_fetch_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .nameRegex(Property.ofValue("prod-.*"))
            .fetchType(Property.ofValue(FetchType.FETCH))
            .build();

        var fetchOut = fetchTask.run(runContext);
        assertNotNull(fetchOut);
        assertThat(fetchOut.getSize(), is(2));
        assertThat(fetchOut.getServers().getFirst().getName(), is("prod-app-01"));

        // 2. FETCH_ONE mode
        var fetchOneTask = ListServers.builder()
            .id("list_servers_one_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .nameRegex(Property.ofValue("prod-.*"))
            .fetchType(Property.ofValue(FetchType.FETCH_ONE))
            .build();

        var oneOut = fetchOneTask.run(runContext);
        assertNotNull(oneOut.getServer());
        assertThat(oneOut.getServer().getName(), is("prod-app-01"));

        // 3. STORE mode
        var storeTask = ListServers.builder()
            .id("list_servers_store_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .fetchType(Property.ofValue(FetchType.STORE))
            .build();

        var storeOut = storeTask.run(runContext);
        assertNotNull(storeOut.getUri());
        assertThat(storeOut.getSize(), is(3));
    }

    @Test
    void testStartServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"os-start\": null}"))
            .willReturn(aResponse().withStatus(202)));

        var runContext = runContextFactory.of();

        var task = StartServer.builder()
            .id("start_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testStopServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"os-stop\": null}"))
            .willReturn(aResponse().withStatus(202)));

        var runContext = runContextFactory.of();

        var task = StopServer.builder()
            .id("stop_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testRebootServerWithEnum() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"reboot\": {\"type\": \"HARD\"}}"))
            .willReturn(aResponse().withStatus(202)));

        var runContext = runContextFactory.of();

        var task = RebootServer.builder()
            .id("reboot_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .rebootType(Property.ofValue(RebootType.HARD))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
        assertThat(output.getRebootType(), is(RebootType.HARD));
    }

    @Test
    void testResetServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"reboot\": {\"type\": \"HARD\"}}"))
            .willReturn(aResponse().withStatus(202)));

        var runContext = runContextFactory.of();

        var task = ResetServer.builder()
            .id("reset_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testSuspendAndResumeServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"suspend\": null}"))
            .willReturn(aResponse().withStatus(202)));

        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"resume\": null}"))
            .willReturn(aResponse().withStatus(202)));

        var runContext = runContextFactory.of();

        var suspendTask = SuspendServer.builder()
            .id("suspend_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        var suspendOut = suspendTask.run(runContext);
        assertThat(suspendOut.getServerId(), is("srv-01"));

        var resumeTask = ResumeServer.builder()
            .id("resume_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        var resumeOut = resumeTask.run(runContext);
        assertThat(resumeOut.getServerId(), is("srv-01"));
    }

    @Test
    void testDeleteServer() throws Exception {
        wireMockServer.stubFor(delete(urlEqualTo("/compute/v2.1/servers/srv-01"))
            .willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of();

        var task = DeleteServer.builder()
            .id("delete_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testGetServerMetadata() throws Exception {
        wireMockServer.stubFor(get(urlEqualTo("/compute/v2.1/servers/srv-01/metadata"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "metadata": {
                        "environment": "staging",
                        "managed_by": "kestra"
                      }
                    }
                    """)));

        var runContext = runContextFactory.of();

        var task = GetServerMetadata.builder()
            .id("get_meta_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getMetadata().get("environment"), is("staging"));
        assertThat(output.getMetadata().get("managed_by"), is("kestra"));
    }

    @Test
    void testUpdateServer() throws Exception {
        wireMockServer.stubFor(put(urlEqualTo("/compute/v2.1/servers/srv-01"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "srv-01",
                        "name": "new-server-name",
                        "status": "ACTIVE",
                        "description": "Updated description"
                      }
                    }
                    """)));

        var runContext = runContextFactory.of();

        var task = UpdateServer.builder()
            .id("update_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .name(Property.ofValue("new-server-name"))
            .serverDescription(Property.ofValue("Updated description"))
            .build();

        var output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
        assertThat(output.getStatus(), is("ACTIVE"));
    }

    @Test
    void testSnapshotLifecycle() throws Exception {
        // Create Snapshot
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(containing("createImage"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"image_id\": \"snap-image-uuid-123\"}")));

        // List Snapshots (Glance)
        wireMockServer.stubFor(get(urlPathEqualTo("/image/v2/images"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "images": [
                        {
                          "id": "snap-image-uuid-123",
                          "name": "backup-2026",
                          "status": "active",
                          "image_type": "snapshot",
                          "instance_uuid": "srv-01"
                        }
                      ]
                    }
                    """)));

        // Restore / Rebuild
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"rebuild\": {\"imageRef\": \"snap-image-uuid-123\"}}"))
            .willReturn(aResponse().withStatus(202)));

        // Delete Snapshot (Glance)
        wireMockServer.stubFor(delete(urlEqualTo("/image/v2/images/snap-image-uuid-123"))
            .willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of();

        // 1. Create snapshot
        var createSnap = CreateServerSnapshot.builder()
            .id("create_snap_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .name(Property.ofValue("backup-2026"))
            .build();
        var createSnapOut = createSnap.run(runContext);
        assertThat(createSnapOut.getImageId(), is("snap-image-uuid-123"));

        // 2. List snapshots
        var listSnap = ListServerSnapshots.builder()
            .id("list_snap_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        var listSnapOut = listSnap.run(runContext);
        assertThat(listSnapOut.getSize(), is(1));
        assertThat(listSnapOut.getSnapshots().getFirst().getId(), is("snap-image-uuid-123"));

        // 3. Restore snapshot
        var restore = RestoreServerFromSnapshot.builder()
            .id("restore_snap_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .imageId(Property.ofValue("snap-image-uuid-123"))
            .build();
        var restoreOut = restore.run(runContext);
        assertThat(restoreOut.getImageId(), is("snap-image-uuid-123"));

        // 4. Delete snapshot
        var deleteSnap = DeleteServerSnapshot.builder()
            .id("del_snap_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .imageId(Property.ofValue("snap-image-uuid-123"))
            .build();
        var deleteSnapOut = deleteSnap.run(runContext);
        assertThat(deleteSnapOut.getImageId(), is("snap-image-uuid-123"));
    }

    @Test
    void testCloneServerWithImageStatusTransition() throws Exception {
        // Source server details
        wireMockServer.stubFor(get(urlEqualTo("/compute/v2.1/servers/source-srv-01"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "source-srv-01",
                        "name": "original-server",
                        "flavor": {"id": "flavor-m1-medium-id"}
                      }
                    }
                    """)));

        // Create snapshot from source
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/source-srv-01/action"))
            .withRequestBody(containing("createImage"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"image_id\": \"snap-clone-image-1\"}")));

        // Snapshot transitions queued -> active via WireMock Scenario
        wireMockServer.stubFor(get(urlEqualTo("/image/v2/images/snap-clone-image-1"))
            .inScenario("SnapshotStatusTransition")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": \"snap-clone-image-1\", \"status\": \"queued\"}"))
            .willSetStateTo("ImageActive"));

        wireMockServer.stubFor(get(urlEqualTo("/image/v2/images/snap-clone-image-1"))
            .inScenario("SnapshotStatusTransition")
            .whenScenarioStateIs("ImageActive")
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\": \"snap-clone-image-1\", \"status\": \"active\"}")));

        // Provision new server from snapshot
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "clone-srv-01",
                        "name": "cloned-server-01",
                        "status": "ACTIVE"
                      }
                    }
                    """)));

        var runContext = runContextFactory.of();

        var cloneTask = CloneServer.builder()
            .id("clone_server_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .sourceServerId(Property.ofValue("source-srv-01"))
            .name(Property.ofValue("cloned-server-01"))
            .build();

        var output = cloneTask.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("clone-srv-01"));
        assertThat(output.getSourceServerId(), is("source-srv-01"));
        assertThat(output.getSnapshotImageId(), is("snap-clone-image-1"));
    }

    @Test
    void testKeystone401Unauthorized() {
        wireMockServer.stubFor(post(urlEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"message\": \"Invalid user or application credentials\"}}")));

        var runContext = runContextFactory.of();

        var task = ListServers.builder()
            .id("unauthorized_task_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("bad-id"))
            .applicationCredentialSecret(Property.ofValue("bad-secret"))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("OpenStack authentication/authorization failed (HTTP 401)"));
    }

    @Test
    void testServer404NotFound() {
        wireMockServer.stubFor(get(urlEqualTo("/compute/v2.1/servers/non-existent-srv"))
            .willReturn(aResponse()
                .withStatus(404)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"itemNotFound\": {\"message\": \"Server not found\"}}")));

        var runContext = runContextFactory.of();

        var task = GetServerMetadata.builder()
            .id("not_found_task_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("non-existent-srv"))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("OpenStack resource not found (HTTP 404)"));
    }

    @Test
    void testEmptyResponseBodyThrowsDescriptiveError() {
        wireMockServer.stubFor(get(urlPathEqualTo("/compute/v2.1/servers/detail"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("")));

        var runContext = runContextFactory.of();

        var task = ListServers.builder()
            .id("empty_body_task_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .build();

        var exception = assertThrows(IllegalStateException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Empty response body received from OpenStack API"));
    }

    @Test
    void testMissingCredentialsThrowsIllegalArgumentException() {
        var runContext = runContextFactory.of();

        var task = ListServers.builder()
            .id("missing_creds_task_" + IdUtils.create())
            .authUrl(Property.ofValue(authUrl))
            .build();

        var exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("Missing credentials"));
    }

    @Test
    void testPebbleVariableRendering() throws Exception {
        wireMockServer.stubFor(delete(urlEqualTo("/compute/v2.1/servers/dyn-srv-999"))
            .willReturn(aResponse().withStatus(204)));

        var runContext = runContextFactory.of(Map.of(
            "myAuthUrl", authUrl,
            "myServerId", "dyn-srv-999"
        ));

        var task = DeleteServer.builder()
            .id("pebble_render_task_" + IdUtils.create())
            .authUrl(Property.ofExpression("{{ myAuthUrl }}"))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofExpression("{{ myServerId }}"))
            .build();

        var output = task.run(runContext);
        assertNotNull(output);
        assertThat(output.getServerId(), is("dyn-srv-999"));
    }
}

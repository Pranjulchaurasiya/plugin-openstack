package io.kestra.plugin.openstack.compute;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.plugin.openstack.client.model.GlanceResponses;
import io.kestra.plugin.openstack.client.model.Server;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
        String tokenBody = String.format("""
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
                  }
                ]
              }
            }
            """, port, port);

        wireMockServer.stubFor(post(urlEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Subject-Token", "mock-openstack-token-xyz123")
                .withBody(tokenBody)));
    }

    @Test
    void testCreateServerWithAppCredentials() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers"))
            .withHeader("X-Auth-Token", equalTo("mock-openstack-token-xyz123"))
            .withHeader("OpenStack-API-Version", equalTo("compute 2.58"))
            .willReturn(aResponse()
                .withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "server": {
                        "id": "srv-9a2f-test-01",
                        "name": "kestra-worker-01",
                        "status": "BUILD",
                        "adminPass": "GeneratedAdminPass123"
                      }
                    }
                    """)));

        RunContext runContext = runContextFactory.of();

        CreateServer task = CreateServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .name(Property.ofValue("kestra-worker-01"))
            .flavorRef(Property.ofValue("m1.medium"))
            .imageRef(Property.ofValue("ubuntu-22.04"))
            .build();

        CreateServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-9a2f-test-01"));
        assertThat(output.getStatus(), is("BUILD"));
        assertThat(output.getAdminPass(), is("GeneratedAdminPass123"));
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

        RunContext runContext = runContextFactory.of();

        CreateServer task = CreateServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .username(Property.ofValue("devuser"))
            .password(Property.ofValue("secretpassword"))
            .projectName(Property.ofValue("platform"))
            .name(Property.ofValue("kestra-worker-02"))
            .flavorRef(Property.ofValue("m1.large"))
            .build();

        CreateServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-9a2f-test-02"));
        assertThat(output.getStatus(), is("ACTIVE"));
    }

    @Test
    void testListServersWithRegex() throws Exception {
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

        RunContext runContext = runContextFactory.of();

        ListServers task = ListServers.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .nameRegex(Property.ofValue("prod-.*"))
            .build();

        ListServers.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getSize(), is(2));
        assertThat(output.getServers().get(0).getName(), is("prod-app-01"));
        assertThat(output.getServers().get(1).getName(), is("prod-db-01"));
    }

    @Test
    void testStartServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"os-start\": null}"))
            .willReturn(aResponse().withStatus(202)));

        RunContext runContext = runContextFactory.of();

        StartServer task = StartServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        StartServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testStopServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"os-stop\": null}"))
            .willReturn(aResponse().withStatus(202)));

        RunContext runContext = runContextFactory.of();

        StopServer task = StopServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        StopServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
    }

    @Test
    void testRebootServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"reboot\": {\"type\": \"HARD\"}}"))
            .willReturn(aResponse().withStatus(202)));

        RunContext runContext = runContextFactory.of();

        RebootServer task = RebootServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .rebootType(Property.ofValue("HARD"))
            .build();

        RebootServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("srv-01"));
        assertThat(output.getType(), is("HARD"));
    }

    @Test
    void testResetServer() throws Exception {
        wireMockServer.stubFor(post(urlEqualTo("/compute/v2.1/servers/srv-01/action"))
            .withRequestBody(equalToJson("{\"reboot\": {\"type\": \"HARD\"}}"))
            .willReturn(aResponse().withStatus(202)));

        RunContext runContext = runContextFactory.of();

        ResetServer task = ResetServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        ResetServer.Output output = task.run(runContext);

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

        RunContext runContext = runContextFactory.of();

        SuspendServer suspendTask = SuspendServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        SuspendServer.Output suspendOut = suspendTask.run(runContext);
        assertThat(suspendOut.getServerId(), is("srv-01"));

        ResumeServer resumeTask = ResumeServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        ResumeServer.Output resumeOut = resumeTask.run(runContext);
        assertThat(resumeOut.getServerId(), is("srv-01"));
    }

    @Test
    void testDeleteServer() throws Exception {
        wireMockServer.stubFor(delete(urlEqualTo("/compute/v2.1/servers/srv-01"))
            .willReturn(aResponse().withStatus(204)));

        RunContext runContext = runContextFactory.of();

        DeleteServer task = DeleteServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        DeleteServer.Output output = task.run(runContext);

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

        RunContext runContext = runContextFactory.of();

        GetServerMetadata task = GetServerMetadata.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();

        GetServerMetadata.Output output = task.run(runContext);

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
                        "description": "Updated description"
                      }
                    }
                    """)));

        RunContext runContext = runContextFactory.of();

        UpdateServer task = UpdateServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .name(Property.ofValue("new-server-name"))
            .serverDescription(Property.ofValue("Updated description"))
            .build();

        UpdateServer.Output output = task.run(runContext);

        assertNotNull(output);
        assertThat(output.getName(), is("new-server-name"));
        assertThat(output.getServerId(), is("srv-01"));
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

        RunContext runContext = runContextFactory.of();

        // 1. Create snapshot
        CreateServerSnapshot createSnap = CreateServerSnapshot.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .name(Property.ofValue("backup-2026"))
            .build();
        CreateServerSnapshot.Output createSnapOut = createSnap.run(runContext);
        assertThat(createSnapOut.getImageId(), is("snap-image-uuid-123"));

        // 2. List snapshots
        ListServerSnapshots listSnap = ListServerSnapshots.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .build();
        ListServerSnapshots.Output listSnapOut = listSnap.run(runContext);
        assertThat(listSnapOut.getSize(), is(1));
        assertThat(listSnapOut.getSnapshots().get(0).getId(), is("snap-image-uuid-123"));

        // 3. Restore snapshot
        RestoreServerFromSnapshot restore = RestoreServerFromSnapshot.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .serverId(Property.ofValue("srv-01"))
            .imageId(Property.ofValue("snap-image-uuid-123"))
            .build();
        RestoreServerFromSnapshot.Output restoreOut = restore.run(runContext);
        assertThat(restoreOut.getImageId(), is("snap-image-uuid-123"));

        // 4. Delete snapshot
        DeleteServerSnapshot deleteSnap = DeleteServerSnapshot.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .imageId(Property.ofValue("snap-image-uuid-123"))
            .build();
        DeleteServerSnapshot.Output deleteSnapOut = deleteSnap.run(runContext);
        assertThat(deleteSnapOut.getImageId(), is("snap-image-uuid-123"));
    }

    @Test
    void testCloneServer() throws Exception {
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
                        "flavor": {"id": "m1.medium"}
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
                        "status": "BUILD"
                      }
                    }
                    """)));

        RunContext runContext = runContextFactory.of();

        CloneServer cloneTask = CloneServer.builder()
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-cred-id"))
            .applicationCredentialSecret(Property.ofValue("app-cred-secret"))
            .sourceServerId(Property.ofValue("source-srv-01"))
            .name(Property.ofValue("cloned-server-01"))
            .build();

        CloneServer.Output output = cloneTask.run(runContext);

        assertNotNull(output);
        assertThat(output.getServerId(), is("clone-srv-01"));
        assertThat(output.getSourceServerId(), is("source-srv-01"));
        assertThat(output.getSnapshotImageId(), is("snap-clone-image-1"));
    }
}

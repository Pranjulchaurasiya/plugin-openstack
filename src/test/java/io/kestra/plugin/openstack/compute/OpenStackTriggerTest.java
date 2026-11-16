package io.kestra.plugin.openstack.compute;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.utils.TestsUtils;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
class OpenStackTriggerTest {

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
                  }
                ]
              }
            }
            """, port);

        wireMockServer.stubFor(post(urlEqualTo("/v3/auth/tokens"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Subject-Token", "mock-token-12345")
                .withBody(tokenBody)));
    }

    @Test
    void testTriggerFiresOnMatchingServerEvent() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/compute/v2.1/servers/detail"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                    {
                      "servers": [
                        {
                          "id": "srv-prod-01",
                          "name": "prod-app-01",
                          "status": "SHUTOFF"
                        }
                      ]
                    }
                    """)));

        Trigger trigger = Trigger.builder()
            .id("watch_openstack")
            .type(Trigger.class.getName())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-id"))
            .applicationCredentialSecret(Property.ofValue("app-secret"))
            .status(Property.ofValue("SHUTOFF"))
            .serverNameRegex(Property.ofValue("prod-.*"))
            .interval(Duration.ofMinutes(1))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> executionOptional = trigger.evaluate(context.getKey(), context.getValue());

        assertTrue(executionOptional.isPresent(), "Trigger should evaluate and return an execution.");
        Execution execution = executionOptional.get();
        assertThat(execution.getTrigger().getVariables().get("size"), is(1));
    }

    @Test
    void testTriggerEmptyWhenNoServersMatch() throws Exception {
        wireMockServer.stubFor(get(urlPathEqualTo("/compute/v2.1/servers/detail"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"servers\": []}")));

        Trigger trigger = Trigger.builder()
            .id("watch_openstack")
            .type(Trigger.class.getName())
            .authUrl(Property.ofValue(authUrl))
            .applicationCredentialId(Property.ofValue("app-id"))
            .applicationCredentialSecret(Property.ofValue("app-secret"))
            .status(Property.ofValue("SHUTOFF"))
            .build();

        var context = TestsUtils.mockTrigger(runContextFactory, trigger);
        Optional<Execution> executionOptional = trigger.evaluate(context.getKey(), context.getValue());
        assertTrue(executionOptional.isEmpty(), "Trigger should return empty when no servers match.");
    }
}

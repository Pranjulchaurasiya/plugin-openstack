package io.kestra.plugin.openstack.compute;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.kestra.plugin.openstack.client.model.Server;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "React to OpenStack server state changes",
    description = """
        Periodically polls OpenStack Nova compute servers for state changes using the `changes-since` filter and fires execution when matching server events occur.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger when a server is stopped",
            full = true,
            code = """
                id: on_server_stopped
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.openstack.compute.Trigger
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    status: "SHUTOFF"
                    serverNameRegex: "prod-.*"
                    interval: PT1M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Detected {{ trigger.size }} stopped server(s)."
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = """
            The duration between consecutive polling cycles. Defaults to `PT1M` (1 minute).
            """
    )
    @PluginProperty(group = "main")
    private Duration interval = Duration.ofMinutes(1);

    @NotNull
    @Schema(
        title = "Keystone authentication URL",
        description = """
            The Identity service endpoint URL for authentication, e.g. `https://keystone.example.com:5000/v3`.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> authUrl;

    @Schema(
        title = "Application Credential ID",
        description = """
            The OpenStack Keystone Application Credential ID for authentication.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> applicationCredentialId;

    @Schema(
        title = "Application Credential Secret",
        description = """
            The OpenStack Keystone Application Credential Secret for authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> applicationCredentialSecret;

    @Schema(
        title = "OpenStack username",
        description = """
            The username for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(
        title = "OpenStack password",
        description = """
            The password for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> password;

    @Schema(
        title = "Target project name",
        description = """
            The OpenStack project/tenant name to scope authentication to when using password credentials.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> projectName;

    @Schema(
        title = "User domain name",
        description = """
            The domain name for the user when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> userDomainName = Property.ofValue("Default");

    @Schema(
        title = "Project domain name",
        description = """
            The domain name for the project when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> projectDomainName = Property.ofValue("Default");

    @Schema(
        title = "Region name",
        description = """
            The OpenStack region to target in the service catalog (e.g. `RegionOne`).
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> region;

    @Schema(
        title = "Endpoint interface type",
        description = """
            The endpoint interface type to resolve from the service catalog: `public`, `internal`, or `admin`. Defaults to `public`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> endpointType = Property.ofValue("public");

    @Schema(
        title = "HTTP client options",
        description = """
            Advanced HTTP client configuration such as timeouts and proxy settings.
            """
    )
    @PluginProperty(group = "connection")
    private HttpConfiguration options;

    @Schema(
        title = "Status filter",
        description = """
            Only match servers in this specific status (e.g. `SHUTOFF`, `ACTIVE`, `ERROR`).
            """
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Server name regex filter",
        description = """
            Optional regular expression to filter server names.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverNameRegex;

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        Map<String, String> queryParams = new HashMap<>();

        if (context.getDate() != null) {
            String isoSince = context.getDate().format(DateTimeFormatter.ISO_INSTANT);
            queryParams.put("changes-since", isoSince);
        }

        String rStatus = runContext.render(this.status).as(String.class).orElse(null);
        if (rStatus != null) {
            queryParams.put("status", rStatus);
        }

        String rNameRegex = runContext.render(this.serverNameRegex).as(String.class).orElse(null);

        try (OpenStackClient client = createClient(runContext)) {
            List<Server> servers = client.listServers(queryParams);

            if (rNameRegex != null && !rNameRegex.isBlank()) {
                Pattern pattern = Pattern.compile(rNameRegex);
                List<Server> filtered = new ArrayList<>();
                for (Server s : servers) {
                    if (s.getName() != null && pattern.matcher(s.getName()).matches()) {
                        filtered.add(s);
                    }
                }
                servers = filtered;
            }

            if (servers.isEmpty()) {
                return Optional.empty();
            }

            logger.info("OpenStack trigger detected {} matching server event(s).", servers.size());

            Output output = Output.builder()
                .servers(servers)
                .size(servers.size())
                .build();

            Execution execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }
    }

    protected OpenStackClient createClient(RunContext runContext) throws Exception {
        return OpenStackClient.builder()
            .runContext(runContext)
            .httpConfiguration(this.options)
            .authUrl(runContext.render(this.authUrl).as(String.class).orElse(null))
            .applicationCredentialId(runContext.render(this.applicationCredentialId).as(String.class).orElse(null))
            .applicationCredentialSecret(runContext.render(this.applicationCredentialSecret).as(String.class).orElse(null))
            .username(runContext.render(this.username).as(String.class).orElse(null))
            .password(runContext.render(this.password).as(String.class).orElse(null))
            .projectName(runContext.render(this.projectName).as(String.class).orElse(null))
            .userDomainName(runContext.render(this.userDomainName).as(String.class).orElse("Default"))
            .projectDomainName(runContext.render(this.projectDomainName).as(String.class).orElse("Default"))
            .region(runContext.render(this.region).as(String.class).orElse(null))
            .endpointType(runContext.render(this.endpointType).as(String.class).orElse("public"))
            .build();
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Matched servers",
            description = """
                List of server instances that triggered the execution.
                """
        )
        private final List<Server> servers;

        @Schema(
            title = "Matched server count",
            description = """
                The number of server events detected.
                """
        )
        private final int size;
    }
}

package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Stop an active OpenStack server",
    description = """
        Sends the `os-stop` action to gracefully shut down an active OpenStack Nova compute server.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Stop an active OpenStack server",
            full = true,
            code = """
                id: stop_openstack_server
                namespace: company.team

                tasks:
                  - id: stop_server
                    type: io.kestra.plugin.openstack.compute.StopServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    serverId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                """
        )
    }
)
public class StopServer extends AbstractComputeTask implements RunnableTask<StopServer.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the server to stop.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rServerId = runContext.render(this.serverId).as(String.class).orElseThrow();

        logger.info("Stopping OpenStack server '{}'...", rServerId);

        try (OpenStackClient client = this.createClient(runContext)) {
            client.stopServer(rServerId);
            logger.info("Successfully dispatched stop action for server '{}'.", rServerId);

            return Output.builder()
                .serverId(rServerId)
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Server UUID",
            description = """
                The UUID of the stopped server.
                """
        )
        private final String serverId;
    }
}

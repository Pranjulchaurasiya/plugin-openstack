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
    title = "Reset (hard reboot) an OpenStack server",
    description = """
        Immediately power cycles (hard reboot) an OpenStack Nova compute server. Equivalent to VMware `ResetVm`.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Hard reset an OpenStack server",
            full = true,
            code = """
                id: reset_openstack_server
                namespace: company.team

                tasks:
                  - id: reset_server
                    type: io.kestra.plugin.openstack.compute.ResetServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    serverId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                """
        )
    }
)
public class ResetServer extends AbstractComputeTask implements RunnableTask<ResetServer.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the server to reset.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rServerId = runContext.render(this.serverId).as(String.class).orElseThrow();

        logger.info("Hard resetting OpenStack server '{}'...", rServerId);

        try (OpenStackClient client = this.createClient(runContext)) {
            client.rebootServer(rServerId, "HARD");
            logger.info("Successfully dispatched hard reset for server '{}'.", rServerId);

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
                The UUID of the reset server.
                """
        )
        private final String serverId;
    }
}

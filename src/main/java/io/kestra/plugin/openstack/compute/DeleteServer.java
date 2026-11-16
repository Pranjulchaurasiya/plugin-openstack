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
    title = "Delete an OpenStack server",
    description = """
        Permanently terminates and deletes an OpenStack Nova compute server.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Delete an OpenStack server",
            full = true,
            code = """
                id: delete_openstack_server
                namespace: company.team

                tasks:
                  - id: delete_server
                    type: io.kestra.plugin.openstack.compute.DeleteServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    serverId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                """
        )
    }
)
public class DeleteServer extends AbstractComputeTask implements RunnableTask<DeleteServer.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the server to delete.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rServerId = runContext.render(this.serverId).as(String.class).orElseThrow();

        logger.info("Deleting OpenStack server '{}'...", rServerId);

        try (OpenStackClient client = this.createClient(runContext)) {
            client.deleteServer(rServerId);
            logger.info("Successfully requested deletion of server '{}'.", rServerId);

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
                The UUID of the deleted server.
                """
        )
        private final String serverId;
    }
}

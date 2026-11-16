package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.kestra.plugin.openstack.client.model.Server;
import io.kestra.plugin.openstack.client.model.ServerPayloads;
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
    title = "Update attributes of an OpenStack server",
    description = """
        Updates the name or description of an existing OpenStack Nova compute server.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Update server name and description",
            full = true,
            code = """
                id: update_openstack_server
                namespace: company.team

                tasks:
                  - id: update_server
                    type: io.kestra.plugin.openstack.compute.UpdateServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    serverId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                    name: "kestra-worker-renamed"
                    description: "Updated by maintenance workflow"
                """
        )
    }
)
public class UpdateServer extends AbstractComputeTask implements RunnableTask<UpdateServer.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the server to update.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverId;

    @Schema(
        title = "New server name",
        description = """
            New name for the server instance.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> name;

    @Schema(
        title = "New server description",
        description = """
            New description for the server instance.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverDescription;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rServerId = runContext.render(this.serverId).as(String.class).orElseThrow();
        String rName = runContext.render(this.name).as(String.class).orElse(null);
        String rDescription = runContext.render(this.serverDescription).as(String.class).orElse(null);

        ServerPayloads.Update payload = ServerPayloads.Update.builder()
            .server(ServerPayloads.UpdateData.builder()
                .name(rName)
                .description(rDescription)
                .build())
            .build();

        logger.info("Updating OpenStack server '{}'...", rServerId);

        try (OpenStackClient client = this.createClient(runContext)) {
            Server server = client.updateServer(rServerId, payload);
            logger.info("Successfully updated server '{}': name='{}'", server.getId(), server.getName());

            return Output.builder()
                .server(server)
                .serverId(server.getId())
                .name(server.getName())
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Updated server representation",
            description = """
                Full details of the updated OpenStack server.
                """
        )
        private final Server server;

        @Schema(
            title = "Server UUID",
            description = """
                The UUID of the updated server.
                """
        )
        private final String serverId;

        @Schema(
            title = "Updated server name",
            description = """
                The new name of the server.
                """
        )
        private final String name;
    }
}

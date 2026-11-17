package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.model.RebootType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Reboot an OpenStack server",
    description = """
        Performs a soft or hard reboot on an OpenStack Nova compute server.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Perform a soft reboot",
            full = true,
            code = """
                id: reboot_openstack_server
                namespace: company.team

                tasks:
                  - id: reboot_server
                    type: io.kestra.plugin.openstack.compute.RebootServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    serverId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                    rebootType: SOFT
                """
        )
    }
)
public class RebootServer extends AbstractComputeTask implements RunnableTask<RebootServer.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the server to reboot.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverId;

    @Schema(
        title = "Reboot type",
        description = """
            Reboot mode: `SOFT` (graceful ACPI request) or `HARD` (power cycle reset). Defaults to `SOFT`.
            """
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<RebootType> rebootType = Property.ofValue(RebootType.SOFT);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rServerId = runContext.render(this.serverId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`serverId` is required"));
        var rType = runContext.render(this.rebootType).as(RebootType.class).orElse(RebootType.SOFT);

        logger.info("Rebooting OpenStack server '{}' (type: {})...", rServerId, rType);

        try (var client = this.createClient(runContext)) {
            client.rebootServer(rServerId, rType.getValue());
            logger.info("Successfully dispatched reboot action for server '{}'.", rServerId);

            return Output.builder()
                .serverId(rServerId)
                .rebootType(rType)
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Server UUID",
            description = """
                The UUID of the rebooted server.
                """
        )
        private final String serverId;

        @Schema(
            title = "Reboot type",
            description = """
                The reboot type dispatched to the instance (`SOFT` or `HARD`).
                """
        )
        private final RebootType rebootType;
    }
}

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

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Reboot an OpenStack server",
    description = """
        Dispatches a soft (graceful ACPI) or hard (power cycle) reboot action to an OpenStack Nova compute server.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Perform a soft reboot of an OpenStack server",
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
            The type of reboot to dispatch (`SOFT` for ACPI graceful reboot, `HARD` for immediate power reset). Defaults to `SOFT`.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<RebootType> rebootType = Property.ofValue(RebootType.SOFT);

    @Schema(
        title = "Wait until server becomes ACTIVE after reboot",
        description = """
            Whether to wait for the server to return to `ACTIVE` status after the reboot. Defaults to `false`.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> waitUntilComplete = Property.ofValue(false);

    @Schema(
        title = "Timeout for reboot completion",
        description = """
            Maximum time to wait for the server to become `ACTIVE` after reboot. Defaults to 10 minutes (`PT10M`).
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(10));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rServerId = runContext.render(this.serverId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`serverId` is required"));
        var rType = runContext.render(this.rebootType).as(RebootType.class).orElse(RebootType.SOFT);
        var rWait = runContext.render(this.waitUntilComplete).as(Boolean.class).orElse(false);
        var rTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofMinutes(10));

        logger.info("Rebooting OpenStack server '{}' (type: {})...", rServerId, rType);

        try (var client = this.createClient(runContext)) {
            client.rebootServer(rServerId, rType.getValue());
            logger.info("Successfully dispatched reboot action for server '{}'.", rServerId);

            if (Boolean.TRUE.equals(rWait)) {
                logger.info("Waiting for server '{}' to become ACTIVE after reboot...", rServerId);
                client.waitForServerStatus(rServerId, "ACTIVE", rTimeout, runContext);
            }

            return Output.builder().build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
    }
}

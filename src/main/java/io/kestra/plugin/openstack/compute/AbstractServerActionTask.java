package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

import java.time.Duration;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractServerActionTask extends AbstractComputeTask implements RunnableTask<AbstractServerActionTask.Output> {

    @NotNull
    @Schema(
        title = "Server UUID",
        description = """
            The unique identifier of the target OpenStack Nova server.
            """
    )
    @PluginProperty(group = "main")
    protected Property<String> serverId;

    @Schema(
        title = "Wait until action completes",
        description = """
            Whether to wait for the server to transition to the expected lifecycle status after the action.
            Defaults to `false`.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    protected Property<Boolean> waitUntilComplete = Property.ofValue(false);

    @Schema(
        title = "Timeout for lifecycle status transition",
        description = """
            Maximum time to wait for the server status transition when `waitUntilComplete` is true.
            Defaults to 10 minutes (`PT10M`).
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    protected Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(10));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rServerId = runContext.render(this.serverId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`serverId` is required"));
        var rWait = runContext.render(this.waitUntilComplete).as(Boolean.class).orElse(false);
        var rTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofMinutes(10));

        logger.info("Executing {} on OpenStack server '{}'...", actionName(), rServerId);

        try (var client = this.createClient(runContext)) {
            executeAction(client, rServerId, runContext);
            logger.info("Successfully executed {} for server '{}'.", actionName(), rServerId);

            if (Boolean.TRUE.equals(rWait) && expectedStatus() != null) {
                logger.info("Waiting for server '{}' to reach status '{}'...", rServerId, expectedStatus());
                client.waitForServerStatus(rServerId, expectedStatus(), rTimeout, runContext);
            }

            return Output.builder().build();
        }
    }

    protected abstract String actionName();

    protected abstract void executeAction(OpenStackClient client, String serverId, RunContext runContext) throws Exception;

    protected String expectedStatus() {
        return null;
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
    }
}

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

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rServerId = runContext.render(this.serverId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`serverId` is required"));

        logger.info("Executing {} on OpenStack server '{}'...", actionName(), rServerId);

        try (var client = this.createClient(runContext)) {
            executeAction(client, rServerId, runContext);
            logger.info("Successfully executed {} for server '{}'.", actionName(), rServerId);

            return Output.builder()
                .serverId(rServerId)
                .build();
        }
    }

    protected abstract String actionName();

    protected abstract void executeAction(OpenStackClient client, String serverId, RunContext runContext) throws Exception;

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Server UUID",
            description = """
                The UUID of the affected server.
                """
        )
        private final String serverId;
    }
}

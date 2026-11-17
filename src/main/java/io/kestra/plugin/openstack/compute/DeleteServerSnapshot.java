package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
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
    title = "Delete an OpenStack server snapshot image",
    description = """
        Permanently deletes a snapshot image from the OpenStack Glance image registry. Equivalent to VMware `DeleteVmSnapshot`.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Delete a snapshot image",
            full = true,
            code = """
                id: delete_snapshot
                namespace: company.team

                tasks:
                  - id: delete_image
                    type: io.kestra.plugin.openstack.compute.DeleteServerSnapshot
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    imageId: "da3052bf-1a52-472e-8c38-ceb5c1fb9811"
                """
        )
    }
)
public class DeleteServerSnapshot extends AbstractComputeTask implements RunnableTask<DeleteServerSnapshot.Output> {

    @NotNull
    @Schema(
        title = "Glance image UUID",
        description = """
            The unique identifier of the snapshot image to delete.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> imageId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rImageId = runContext.render(this.imageId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`imageId` is required for DeleteServerSnapshot"));

        logger.info("Deleting Glance snapshot image '{}'...", rImageId);

        try (var client = this.createClient(runContext)) {
            client.deleteSnapshot(rImageId);
            logger.info("Successfully deleted snapshot image '{}'.", rImageId);

            return Output.builder()
                .imageId(rImageId)
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Deleted image UUID",
            description = """
                The UUID of the deleted snapshot image.
                """
        )
        private final String imageId;
    }
}

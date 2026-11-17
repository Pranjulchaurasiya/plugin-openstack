package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.model.Server;
import io.kestra.plugin.openstack.client.model.ServerPayloads;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Clone an OpenStack server",
    description = """
        Clones an existing OpenStack server by creating a snapshot image, waiting for it to become active, and provisioning a new server instance from that snapshot. Equivalent to VMware `CloneVm`.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Clone a production server for dev/test",
            full = true,
            code = """
                id: clone_server
                namespace: company.team

                tasks:
                  - id: clone
                    type: io.kestra.plugin.openstack.compute.CloneServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    sourceServerId: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                    name: "kestra-worker-clone-01"
                """
        )
    }
)
public class CloneServer extends AbstractComputeTask implements RunnableTask<CloneServer.Output> {

    @NotNull
    @Schema(
        title = "Source server UUID",
        description = """
            The unique identifier of the existing server to clone.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> sourceServerId;

    @NotNull
    @Schema(
        title = "Cloned server name",
        description = """
            The name to assign to the new cloned server instance.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> name;

    @Schema(
        title = "Flavor reference or ID",
        description = """
            Optional flavor ID or name for the cloned server. If omitted, uses the source server's flavor ID.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> flavorRef;

    @Schema(
        title = "Network attachments",
        description = """
            Optional network attachments for the cloned server.
            """
    )
    @PluginProperty(group = "main")
    private Property<List<CreateServer.NetworkAttachment>> networks;

    @Schema(
        title = "Intermediate snapshot image name",
        description = """
            Name to assign to the intermediate Glance snapshot. Defaults to `{name}-snapshot`.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> snapshotName;

    @Schema(
        title = "Delete snapshot after cloning",
        description = """
            Whether to automatically delete the intermediate Glance snapshot image after the clone server becomes ACTIVE. Defaults to `false`.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> deleteSnapshotAfterClone = Property.ofValue(false);

    @Schema(
        title = "Timeout duration",
        description = """
            Maximum duration to wait for intermediate snapshot and server provisioning. Defaults to 10 minutes.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Duration> timeout = Property.ofValue(Duration.ofMinutes(10));

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();
        var rSourceServerId = runContext.render(this.sourceServerId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`sourceServerId` is required for CloneServer"));
        var rName = runContext.render(this.name).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("`name` is required for CloneServer"));
        var rFlavorRef = runContext.render(this.flavorRef).as(String.class).orElse(null);
        var rSnapshotName = runContext.render(this.snapshotName).as(String.class).orElse(rName + "-snapshot");
        var rDeleteSnap = runContext.render(this.deleteSnapshotAfterClone).as(Boolean.class).orElse(false);
        var rTimeout = runContext.render(this.timeout).as(Duration.class).orElse(Duration.ofMinutes(10));

        var rNetworks = runContext.render(this.networks).asList(CreateServer.NetworkAttachment.class);
        List<ServerPayloads.NetworkRef> networkRefs = null;
        if (rNetworks != null && !rNetworks.isEmpty()) {
            networkRefs = new ArrayList<>();
            for (var net : rNetworks) {
                networkRefs.add(ServerPayloads.NetworkRef.builder()
                    .uuid(net.getUuid())
                    .port(net.getPort())
                    .fixedIp(net.getFixedIp())
                    .tag(net.getTag())
                    .build());
            }
        }

        logger.info("Cloning OpenStack server '{}' to new server '{}'...", rSourceServerId, rName);

        try (var client = this.createClient(runContext)) {
            if (rFlavorRef == null) {
                var sourceServer = client.getServer(rSourceServerId);
                if (sourceServer.getFlavor() != null && sourceServer.getFlavor().get("id") != null) {
                    rFlavorRef = sourceServer.getFlavor().get("id").toString();
                } else {
                    throw new IllegalStateException("Could not resolve flavor ID from source server '" + rSourceServerId + "'.");
                }
            } else {
                rFlavorRef = client.resolveFlavorId(rFlavorRef);
            }

            logger.info("Creating intermediate snapshot image '{}' from server '{}'...", rSnapshotName, rSourceServerId);
            var snapshotImageId = client.createSnapshot(rSourceServerId, rSnapshotName, null);

            logger.info("Waiting for snapshot image '{}' to reach active status...", snapshotImageId);
            client.waitForImageActive(snapshotImageId, rTimeout, runContext);
            logger.info("Snapshot image '{}' is now active.", snapshotImageId);

            logger.info("Provisioning cloned server '{}' from snapshot image '{}'...", rName, snapshotImageId);
            var createPayload = ServerPayloads.Create.builder()
                .server(ServerPayloads.ServerData.builder()
                    .name(rName)
                    .flavorRef(rFlavorRef)
                    .imageRef(snapshotImageId)
                    .networks(networkRefs)
                    .build())
                .build();

            var clonedServer = client.createServer(createPayload);
            logger.info("Initiated clone server: id='{}', name='{}'", clonedServer.getId(), clonedServer.getName());

            if (Boolean.TRUE.equals(rDeleteSnap) && snapshotImageId != null) {
                logger.info("Waiting for cloned server '{}' to become ACTIVE before deleting snapshot '{}'...", clonedServer.getId(), snapshotImageId);
                clonedServer = client.waitForServerStatus(clonedServer.getId(), "ACTIVE", rTimeout, runContext);
                try {
                    client.deleteSnapshot(snapshotImageId);
                    logger.info("Successfully deleted intermediate snapshot image '{}'.", snapshotImageId);
                } catch (Exception e) {
                    logger.warn("Could not delete intermediate snapshot image '{}': {}", snapshotImageId, e.getMessage());
                }
            }

            return Output.builder()
                .serverId(clonedServer.getId())
                .status(clonedServer.getStatus())
                .sourceServerId(rSourceServerId)
                .snapshotImageId(snapshotImageId)
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Cloned server UUID",
            description = """
                The unique identifier of the cloned server.
                """
        )
        private final String serverId;

        @Schema(
            title = "Cloned server status",
            description = """
                The current execution status of the cloned server.
                """
        )
        private final String status;

        @Schema(
            title = "Source server UUID",
            description = """
                The unique identifier of the source server that was cloned.
                """
        )
        private final String sourceServerId;

        @Schema(
            title = "Intermediate snapshot image UUID",
            description = """
                The Glance image UUID of the snapshot created during the cloning process.
                """
        )
        private final String snapshotImageId;
    }
}

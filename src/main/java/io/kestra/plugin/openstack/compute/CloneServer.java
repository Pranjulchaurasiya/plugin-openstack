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
        Clones an existing OpenStack server by creating a snapshot image and provisioning a new server instance from that snapshot. Equivalent to VMware `CloneVm`.
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
            Whether to automatically delete the intermediate Glance snapshot image after the clone server is requested. Defaults to `false`.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Boolean> deleteSnapshotAfterClone = Property.ofValue(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();
        String rSourceServerId = runContext.render(this.sourceServerId).as(String.class).orElseThrow();
        String rName = runContext.render(this.name).as(String.class).orElseThrow();
        String rFlavorRef = runContext.render(this.flavorRef).as(String.class).orElse(null);
        String rSnapshotName = runContext.render(this.snapshotName).as(String.class).orElse(rName + "-snapshot");
        Boolean rDeleteSnap = runContext.render(this.deleteSnapshotAfterClone).as(Boolean.class).orElse(false);

        List<CreateServer.NetworkAttachment> rNetworks = runContext.render(this.networks).asList(CreateServer.NetworkAttachment.class);
        List<ServerPayloads.NetworkRef> networkRefs = null;
        if (rNetworks != null && !rNetworks.isEmpty()) {
            networkRefs = new ArrayList<>();
            for (CreateServer.NetworkAttachment net : rNetworks) {
                networkRefs.add(ServerPayloads.NetworkRef.builder()
                    .uuid(net.getUuid())
                    .port(net.getPort())
                    .fixedIp(net.getFixedIp())
                    .tag(net.getTag())
                    .build());
            }
        }

        logger.info("Cloning server '{}' to new server '{}'...", rSourceServerId, rName);

        try (OpenStackClient client = this.createClient(runContext)) {
            // If flavor is not specified, query source server to find its flavor ID
            if (rFlavorRef == null) {
                Server sourceServer = client.getServer(rSourceServerId);
                if (sourceServer.getFlavor() != null && sourceServer.getFlavor().get("id") != null) {
                    rFlavorRef = sourceServer.getFlavor().get("id").toString();
                } else {
                    throw new IllegalStateException("Could not resolve flavor ID from source server '" + rSourceServerId + "'.");
                }
            }

            // Step 1: Create snapshot
            logger.info("Step 1/2: Creating snapshot image '{}' from source server '{}'...", rSnapshotName, rSourceServerId);
            String snapshotImageId = client.createSnapshot(rSourceServerId, rSnapshotName, null);

            // Step 2: Create server from snapshot image
            logger.info("Step 2/2: Provisioning cloned server '{}' from snapshot image '{}'...", rName, snapshotImageId);
            ServerPayloads.Create createPayload = ServerPayloads.Create.builder()
                .server(ServerPayloads.ServerData.builder()
                    .name(rName)
                    .flavorRef(rFlavorRef)
                    .imageRef(snapshotImageId)
                    .networks(networkRefs)
                    .build())
                .build();

            Server clonedServer = client.createServer(createPayload);
            logger.info("Successfully initiated clone server: id='{}', name='{}'", clonedServer.getId(), clonedServer.getName());

            if (Boolean.TRUE.equals(rDeleteSnap) && snapshotImageId != null) {
                try {
                    client.deleteSnapshot(snapshotImageId);
                    logger.debug("Deleted intermediate snapshot image '{}'.", snapshotImageId);
                } catch (Exception e) {
                    logger.warn("Could not delete intermediate snapshot image '{}': {}", snapshotImageId, e.getMessage());
                }
            }

            return Output.builder()
                .server(clonedServer)
                .serverId(clonedServer.getId())
                .sourceServerId(rSourceServerId)
                .snapshotImageId(snapshotImageId)
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Cloned server representation",
            description = """
                Full details of the newly created cloned server instance.
                """
        )
        private final Server server;

        @Schema(
            title = "Cloned server UUID",
            description = """
                The unique identifier of the cloned server.
                """
        )
        private final String serverId;

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

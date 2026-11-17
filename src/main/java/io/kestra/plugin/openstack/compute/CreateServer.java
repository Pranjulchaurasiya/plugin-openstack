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
import java.util.Map;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Create an OpenStack Nova compute server",
    description = """
        Provisions a new virtual server instance in OpenStack Nova using the specified flavor, image, and network configurations.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Create an OpenStack virtual server",
            full = true,
            code = """
                id: create_openstack_server
                namespace: company.team

                tasks:
                  - id: create_server
                    type: io.kestra.plugin.openstack.compute.CreateServer
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    name: "kestra-worker-01"
                    flavorRef: "m1.medium"
                    imageRef: "ubuntu-22.04"
                    networks:
                      - uuid: "9a2f6dc1-b1e8-45d2-a745-cfa098650392"
                    keyName: "deployer-key"
                """
        )
    }
)
public class CreateServer extends AbstractComputeTask implements RunnableTask<CreateServer.Output> {

    @NotNull
    @Schema(
        title = "Server display name",
        description = """
            The human-readable name of the server to create.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> name;

    @NotNull
    @Schema(
        title = "Flavor reference or ID",
        description = """
            The ID or name of the flavor specifying compute vCPU, RAM, and disk size.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> flavorRef;

    @Schema(
        title = "Image reference or ID",
        description = """
            The UUID or URN of the Glance operating system image to boot from.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> imageRef;

    @Schema(
        title = "Network attachments",
        description = """
            List of network definitions (such as network UUID or port UUID) to connect to the server interface.
            """
    )
    @PluginProperty(group = "main")
    private Property<List<NetworkAttachment>> networks;

    @Schema(
        title = "SSH Keypair name",
        description = """
            The name of an existing OpenStack keypair to inject into the server for SSH authentication.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> keyName;

    @Schema(
        title = "User data / cloud-init script",
        description = """
            Cloud-init configuration script or shell script to execute on boot. Will be automatically base64-encoded if plain text.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> userData;

    @Schema(
        title = "Server metadata",
        description = """
            Key-value metadata pairs to attach to the server.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Map<String, String>> metadata;

    @Schema(
        title = "Admin password",
        description = """
            Optional root/administrator password to assign to the instance on creation.
            """
    )
    @PluginProperty(group = "advanced", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> adminPass;

    @Schema(
        title = "Server description",
        description = """
            Extended text description of the server instance.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> serverDescription;

    @Schema(
        title = "Server tags",
        description = """
            List of tags to assign to the server.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<List<String>> tags;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rName = runContext.render(this.name).as(String.class).orElseThrow();
        String rFlavorRef = runContext.render(this.flavorRef).as(String.class).orElseThrow();
        String rImageRef = runContext.render(this.imageRef).as(String.class).orElse(null);
        String rKeyName = runContext.render(this.keyName).as(String.class).orElse(null);
        String rUserData = runContext.render(this.userData).as(String.class).orElse(null);
        String rAdminPass = runContext.render(this.adminPass).as(String.class).orElse(null);
        String rDescription = runContext.render(this.serverDescription).as(String.class).orElse(null);
        Map<String, String> rMetadata = runContext.render(this.metadata).asMap(String.class, String.class);
        List<String> rTags = runContext.render(this.tags).asList(String.class);

        List<ServerPayloads.NetworkRef> networkRefs = null;
        List<NetworkAttachment> rNetworks = runContext.render(this.networks).asList(NetworkAttachment.class);
        if (rNetworks != null && !rNetworks.isEmpty()) {
            networkRefs = new ArrayList<>();
            for (NetworkAttachment net : rNetworks) {
                networkRefs.add(ServerPayloads.NetworkRef.builder()
                    .uuid(net.getUuid())
                    .port(net.getPort())
                    .fixedIp(net.getFixedIp())
                    .tag(net.getTag())
                    .build());
            }
        }

        ServerPayloads.ServerData serverData = ServerPayloads.ServerData.builder()
            .name(rName)
            .flavorRef(rFlavorRef)
            .imageRef(rImageRef)
            .networks(networkRefs)
            .keyName(rKeyName)
            .userData(rUserData)
            .metadata(rMetadata != null && !rMetadata.isEmpty() ? rMetadata : null)
            .adminPass(rAdminPass)
            .description(rDescription)
            .tags(rTags != null && !rTags.isEmpty() ? rTags : null)
            .build();

        ServerPayloads.Create payload = ServerPayloads.Create.builder()
            .server(serverData)
            .build();

        logger.info("Creating OpenStack compute server '{}' with flavor '{}'...", rName, rFlavorRef);

        try (OpenStackClient client = this.createClient(runContext)) {
            Server server = client.createServer(payload);
            logger.info("Successfully requested server creation: id='{}', name='{}', status='{}'",
                server.getId(), server.getName(), server.getStatus());

            return Output.builder()
                .server(server)
                .serverId(server.getId())
                .status(server.getStatus())
                .build();
        }
    }

    @Getter
    @Builder
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NetworkAttachment {
        @Schema(
            title = "Network UUID",
            description = """
                The UUID of the Neutron network to connect.
                """
        )
        private String uuid;

        @Schema(
            title = "Port UUID",
            description = """
                The UUID of an existing Neutron port to attach.
                """
        )
        private String port;

        @Schema(
            title = "Fixed IP",
            description = """
                The fixed IPv4 or IPv6 address to assign to the network interface.
                """
        )
        private String fixedIp;

        @Schema(
            title = "Device Tag",
            description = """
                An arbitrary tag to identify the network device within guest metadata.
                """
        )
        private String tag;
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Created server representation",
            description = """
                Full details of the created OpenStack Nova server.
                """
        )
        private final Server server;

        @Schema(
            title = "Server UUID",
            description = """
                The unique identifier of the created server.
                """
        )
        private final String serverId;

        @Schema(
            title = "Server status",
            description = """
                The initial status of the server (e.g. `BUILD` or `ACTIVE`).
                """
        )
        private final String status;
    }
}

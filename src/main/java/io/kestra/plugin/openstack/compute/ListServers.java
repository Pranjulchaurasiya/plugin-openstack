package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.kestra.plugin.openstack.client.SafeRegex;
import io.kestra.plugin.openstack.client.model.Server;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "List OpenStack compute servers",
    description = """
        Queries OpenStack Nova for detailed server instances, with optional status, name, regex, and changes-since filtering.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "List all active OpenStack servers",
            full = true,
            code = """
                id: list_openstack_servers
                namespace: company.team

                tasks:
                  - id: list_servers
                    type: io.kestra.plugin.openstack.compute.ListServers
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    status: "ACTIVE"
                    nameRegex: "kestra-.*"
                """
        )
    }
)
public class ListServers extends AbstractComputeTask implements RunnableTask<ListServers.Output> {

    @Schema(
        title = "Server status filter",
        description = """
            Filter servers by their current status (e.g. `ACTIVE`, `SHUTOFF`, `PAUSED`, `ERROR`).
            """
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Server name filter",
        description = """
            Filter servers by exact name or prefix in the OpenStack API query.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> name;

    @Schema(
        title = "Server name regex filter",
        description = """
            Optional regular expression to filter server names in Kestra after retrieval.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> nameRegex;

    @Schema(
        title = "Changes since filter",
        description = """
            ISO 8601 timestamp string (e.g. `2026-11-16T00:00:00Z`). Only servers updated since this time will be returned.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> changesSince;

    @Schema(
        title = "Results limit",
        description = """
            Maximum number of server records to return.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<Integer> limit;

    @Schema(
        title = "Pagination marker",
        description = """
            The ID of the last server on the previous page for cursor pagination.
            """
    )
    @PluginProperty(group = "advanced")
    private Property<String> marker;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String rStatus = runContext.render(this.status).as(String.class).orElse(null);
        String rName = runContext.render(this.name).as(String.class).orElse(null);
        String rNameRegex = runContext.render(this.nameRegex).as(String.class).orElse(null);
        String rChangesSince = runContext.render(this.changesSince).as(String.class).orElse(null);
        Integer rLimit = runContext.render(this.limit).as(Integer.class).orElse(null);
        String rMarker = runContext.render(this.marker).as(String.class).orElse(null);

        Map<String, String> queryParams = new HashMap<>();
        if (rStatus != null) {
            queryParams.put("status", rStatus);
        }
        if (rName != null) {
            queryParams.put("name", rName);
        }
        if (rChangesSince != null) {
            queryParams.put("changes-since", rChangesSince);
        }
        if (rLimit != null) {
            queryParams.put("limit", String.valueOf(rLimit));
        }
        if (rMarker != null) {
            queryParams.put("marker", rMarker);
        }

        logger.debug("Listing OpenStack servers with parameters: {}", queryParams);

        try (OpenStackClient client = this.createClient(runContext)) {
            List<Server> servers = client.listServers(queryParams);

            if (rNameRegex != null && !rNameRegex.isBlank()) {
                Pattern pattern = SafeRegex.compile(rNameRegex);
                List<Server> filtered = new ArrayList<>();
                for (Server s : servers) {
                    if (s.getName() != null && SafeRegex.matches(pattern, s.getName())) {
                        filtered.add(s);
                    }
                }
                servers = filtered;
            }

            logger.info("Found {} OpenStack compute server(s).", servers.size());

            return Output.builder()
                .servers(servers)
                .size(servers.size())
                .build();
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Retrieved servers",
            description = """
                List of detailed server objects matching the query criteria.
                """
        )
        private final List<Server> servers;

        @Schema(
            title = "Number of servers found",
            description = """
                The count of servers returned.
                """
        )
        private final int size;
    }
}

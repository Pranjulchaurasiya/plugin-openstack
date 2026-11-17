package io.kestra.plugin.openstack.compute;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.FileSerde;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.kestra.plugin.openstack.client.SafeRegex;
import io.kestra.plugin.openstack.client.model.Server;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.net.URI;
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
        Queries OpenStack Nova for detailed server instances, supporting marker pagination and FETCH / FETCH_ONE / STORE output modes.
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
                    fetchType: FETCH
                """
        )
    }
)
public class ListServers extends AbstractComputeTask implements RunnableTask<ListServers.Output> {

    @Schema(
        title = "Fetch mode",
        description = """
            Output retrieval type: `FETCH` (in-memory list), `FETCH_ONE` (first matched server), or `STORE` (persisted to internal storage). Defaults to `FETCH`.
            """
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<FetchType> fetchType = Property.ofValue(FetchType.FETCH);

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
        var logger = runContext.logger();

        var rFetchType = runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.FETCH);
        var rStatus = runContext.render(this.status).as(String.class).orElse(null);
        var rName = runContext.render(this.name).as(String.class).orElse(null);
        var rNameRegex = runContext.render(this.nameRegex).as(String.class).orElse(null);
        var rChangesSince = runContext.render(this.changesSince).as(String.class).orElse(null);
        var rLimit = runContext.render(this.limit).as(Integer.class).orElse(null);
        var rMarker = runContext.render(this.marker).as(String.class).orElse(null);

        var queryParams = new HashMap<String, String>();
        if (rStatus != null) {
            queryParams.put("status", rStatus);
        }
        if (rName != null) {
            queryParams.put("name", rName);
        }
        if (rChangesSince != null) {
            queryParams.put("changes-since", rChangesSince);
        }

        var allServers = new ArrayList<Server>();
        var currentMarker = rMarker;
        var pageSize = rLimit != null ? Math.min(rLimit, 100) : 100;

        try (var client = this.createClient(runContext)) {
            while (true) {
                var pageParams = new HashMap<>(queryParams);
                pageParams.put("limit", String.valueOf(pageSize));
                if (currentMarker != null) {
                    pageParams.put("marker", currentMarker);
                }

                var batch = client.listServers(pageParams);
                if (batch == null || batch.isEmpty()) {
                    break;
                }

                allServers.addAll(batch);

                if (rLimit != null && allServers.size() >= rLimit) {
                    allServers = new ArrayList<>(allServers.subList(0, rLimit));
                    break;
                }

                if (batch.size() < pageSize) {
                    break;
                }

                currentMarker = batch.get(batch.size() - 1).getId();
            }

            if (rNameRegex != null && !rNameRegex.isBlank()) {
                var pattern = SafeRegex.compile(rNameRegex);
                var filtered = new ArrayList<Server>();
                for (var s : allServers) {
                    if (s.getName() != null && SafeRegex.matches(pattern, s.getName())) {
                        filtered.add(s);
                    }
                }
                allServers = filtered;
            }

            logger.info("Found {} OpenStack compute server(s). FetchType: {}", allServers.size(), rFetchType);

            var builder = Output.builder().size(allServers.size());

            if (rFetchType == FetchType.FETCH_ONE) {
                var single = allServers.isEmpty() ? null : allServers.get(0);
                return builder.server(single).build();
            } else if (rFetchType == FetchType.STORE) {
                var tempFile = runContext.workingDir().createTempFile(".ion").toFile();
                try (var output = new BufferedOutputStream(new FileOutputStream(tempFile))) {
                    for (var s : allServers) {
                        FileSerde.write(output, s);
                    }
                }
                var uri = runContext.storage().putFile(tempFile);
                return builder.uri(uri).build();
            } else {
                return builder.servers(allServers).build();
            }
        }
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Retrieved servers",
            description = """
                List of detailed server objects matching the query criteria (populated when fetchType is `FETCH`).
                """
        )
        private final List<Server> servers;

        @Schema(
            title = "Single server",
            description = """
                First matched server object (populated when fetchType is `FETCH_ONE`).
                """
        )
        private final Server server;

        @Schema(
            title = "Internal storage URI",
            description = """
                URI of stored results file in Kestra internal storage (populated when fetchType is `STORE`).
                """
        )
        private final URI uri;

        @Schema(
            title = "Number of servers found",
            description = """
                The count of servers returned or stored.
                """
        )
        private final int size;
    }
}

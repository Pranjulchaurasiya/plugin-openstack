package io.kestra.plugin.openstack.compute;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVMetadata;
import io.kestra.core.storages.kv.KVValueAndMetadata;
import io.kestra.plugin.openstack.client.OpenStackClient;
import io.kestra.plugin.openstack.client.SafeRegex;
import io.kestra.plugin.openstack.client.model.EndpointType;
import io.kestra.plugin.openstack.client.model.EventType;
import io.kestra.plugin.openstack.client.model.Server;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "React to OpenStack server state changes",
    description = """
        Periodically polls OpenStack Nova compute servers for state changes using KV Store watermarking and event filtering, dispatching execution with compact event payloads.
        """
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger when a server is stopped",
            full = true,
            code = """
                id: on_server_stopped
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.openstack.compute.Trigger
                    authUrl: "https://keystone.company.local:5000/v3"
                    applicationCredentialId: "{{ secret('OPENSTACK_APP_CREDENTIAL_ID') }}"
                    applicationCredentialSecret: "{{ secret('OPENSTACK_APP_CREDENTIAL_SECRET') }}"
                    status: "SHUTOFF"
                    serverNameRegex: "prod-.*"
                    eventType: STOP
                    interval: PT1M

                tasks:
                  - id: log
                    type: io.kestra.plugin.core.log.Log
                    message: "Detected {{ trigger.size }} server event(s)."
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output>, OpenStackConnectionInterface {

    @Builder.Default
    @Schema(
        title = "Polling interval",
        description = """
            The duration between consecutive polling cycles. Defaults to `PT1M` (1 minute).
            """
    )
    @PluginProperty(group = "main")
    private Duration interval = Duration.ofMinutes(1);

    @NotNull
    @Schema(
        title = "Keystone authentication URL",
        description = """
            The Identity service endpoint URL for authentication, e.g. `https://keystone.example.com:5000/v3`.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> authUrl;

    @Schema(
        title = "Application Credential ID",
        description = """
            The OpenStack Keystone Application Credential ID for authentication.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> applicationCredentialId;

    @Schema(
        title = "Application Credential Secret",
        description = """
            The OpenStack Keystone Application Credential Secret for authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> applicationCredentialSecret;

    @Schema(
        title = "OpenStack username",
        description = """
            The username for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> username;

    @Schema(
        title = "OpenStack password",
        description = """
            The password for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Property<String> password;

    @Schema(
        title = "Target project name",
        description = """
            The OpenStack project/tenant name to scope authentication to when using password credentials.
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> projectName;

    @Schema(
        title = "User domain name",
        description = """
            The domain name for the user when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> userDomainName = Property.ofValue("Default");

    @Schema(
        title = "Project domain name",
        description = """
            The domain name for the project when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<String> projectDomainName = Property.ofValue("Default");

    @Schema(
        title = "Region name",
        description = """
            The OpenStack region to target in the service catalog (e.g. `RegionOne`).
            """
    )
    @PluginProperty(group = "connection")
    private Property<String> region;

    @Schema(
        title = "Endpoint interface type",
        description = """
            The endpoint interface type to resolve from the service catalog: `PUBLIC`, `INTERNAL`, or `ADMIN`. Defaults to `PUBLIC`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    private Property<EndpointType> endpointType = Property.ofValue(EndpointType.PUBLIC);

    @Schema(
        title = "HTTP client options",
        description = """
            Advanced HTTP client configuration such as timeouts and proxy settings.
            """
    )
    @PluginProperty(group = "connection")
    private HttpConfiguration options;

    @Schema(
        title = "Status filter",
        description = """
            Only match servers in this specific status (e.g. `SHUTOFF`, `ACTIVE`, `ERROR`).
            """
    )
    @PluginProperty(group = "main")
    private Property<String> status;

    @Schema(
        title = "Server name regex filter",
        description = """
            Optional regular expression to filter server names.
            """
    )
    @PluginProperty(group = "main")
    private Property<String> serverNameRegex;

    @Schema(
        title = "Event type filter",
        description = """
            Filter for specific server lifecycle events (e.g. `ALL`, `START`, `STOP`, `REBOOT`, `DELETE`). Defaults to `ALL`.
            """
    )
    @PluginProperty(group = "main")
    @Builder.Default
    private Property<EventType> eventType = Property.ofValue(EventType.ALL);

    @Schema(
        title = "Max events per poll",
        description = """
            Maximum number of server events to emit in a single trigger execution. Defaults to 100.
            """
    )
    @PluginProperty(group = "advanced")
    @Builder.Default
    private Property<Integer> maxEvents = Property.ofValue(100);

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext context) throws Exception {
        var runContext = conditionContext.getRunContext();
        var logger = runContext.logger();

        var rStatus = runContext.render(this.status).as(String.class).orElse(null);
        var rNameRegex = runContext.render(this.serverNameRegex).as(String.class).orElse(null);
        var rEventType = runContext.render(this.eventType).as(EventType.class).orElse(EventType.ALL);
        var rMaxEvents = runContext.render(this.maxEvents).as(Integer.class).orElse(100);

        var kvKey = ("watermark_" + conditionContext.getFlow().getId() + "_" + this.getId()).replaceAll("[^a-zA-Z0-9_.-]", "_");
        var kvStore = runContext.namespaceKv(conditionContext.getFlow().getNamespace());

        String watermark = null;
        try {
            var valOpt = kvStore.getValue(kvKey);
            if (valOpt.isPresent() && valOpt.get().value() != null) {
                watermark = valOpt.get().value().toString();
            }
        } catch (Exception e) {
            logger.debug("No previous watermark found in KV Store for key '{}': {}", kvKey, e.getMessage());
        }

        if (watermark == null && context.getDate() != null) {
            watermark = context.getDate().format(DateTimeFormatter.ISO_INSTANT);
        }

        var queryParams = new HashMap<String, String>();
        if (watermark != null) {
            queryParams.put("changes-since", watermark);
        }
        if (rStatus != null) {
            queryParams.put("status", rStatus);
        }

        try (var client = OpenStackConnectionInterface.createClient(this, runContext)) {
            var servers = client.listServers(queryParams);

            if (rNameRegex != null && !rNameRegex.isBlank()) {
                var pattern = SafeRegex.compile(rNameRegex);
                var filtered = new ArrayList<Server>();
                for (var s : servers) {
                    if (s.getName() != null && SafeRegex.matches(pattern, s.getName())) {
                        filtered.add(s);
                    }
                }
                servers = filtered;
            }

            var events = new ArrayList<ServerEvent>();
            String latestTimestamp = watermark;

            for (var srv : servers) {
                if (events.size() >= rMaxEvents) {
                    break;
                }

                // Check instance actions for specific event detection
                List<io.kestra.plugin.openstack.client.model.InstanceActionResponses.InstanceAction> actions = Collections.emptyList();
                try {
                    actions = client.listInstanceActions(srv.getId(), watermark);
                } catch (Exception ignored) {
                }

                if (!actions.isEmpty()) {
                    for (var act : actions) {
                        if (events.size() >= rMaxEvents) {
                            break;
                        }
                        if (matchesEvent(act.getAction(), rEventType)) {
                            events.add(ServerEvent.builder()
                                .serverId(srv.getId())
                                .serverName(srv.getName())
                                .eventType(act.getAction())
                                .requestId(act.getRequestId())
                                .timestamp(act.getStartTime() != null ? act.getStartTime() : srv.getUpdated())
                                .build());
                        }
                    }
                } else {
                    // Fallback to server state change event
                    var eventName = srv.getStatus() != null ? srv.getStatus() : "UPDATE";
                    if (rEventType == EventType.ALL || matchesStatusEvent(srv.getStatus(), rEventType)) {
                        events.add(ServerEvent.builder()
                            .serverId(srv.getId())
                            .serverName(srv.getName())
                            .eventType(eventName)
                            .requestId(null)
                            .timestamp(srv.getUpdated())
                            .build());
                    }
                }

                if (srv.getUpdated() != null && (latestTimestamp == null || srv.getUpdated().compareTo(latestTimestamp) > 0)) {
                    latestTimestamp = srv.getUpdated();
                }
            }

            // Advance watermark if events detected or timestamp moved forward
            if (latestTimestamp != null && !latestTimestamp.equals(watermark)) {
                try {
                    kvStore.put(kvKey, new KVValueAndMetadata(new KVMetadata(null, (Duration) null), latestTimestamp));
                    logger.debug("Updated OpenStack trigger watermark to '{}'.", latestTimestamp);
                } catch (Exception e) {
                    logger.warn("Failed to persist watermark '{}' in KV store: {}", latestTimestamp, e.getMessage());
                }
            }

            if (events.isEmpty()) {
                return Optional.empty();
            }

            logger.info("OpenStack trigger detected {} matching server event(s).", events.size());

            var output = Output.builder()
                .events(events)
                .size(events.size())
                .build();

            var execution = TriggerService.generateExecution(this, conditionContext, context, output);
            return Optional.of(execution);
        }
    }

    private boolean matchesEvent(String action, EventType filter) {
        if (filter == EventType.ALL || action == null) {
            return true;
        }
        var act = action.toLowerCase(Locale.ROOT);
        return switch (filter) {
            case CREATE -> act.contains("create");
            case START -> act.contains("start");
            case STOP -> act.contains("stop");
            case REBOOT -> act.contains("reboot");
            case PAUSE -> act.contains("pause");
            case SUSPEND -> act.contains("suspend");
            case RESUME -> act.contains("resume");
            case DELETE -> act.contains("delete");
            case RESIZE -> act.contains("resize");
            case REBUILD -> act.contains("rebuild");
            default -> true;
        };
    }

    private boolean matchesStatusEvent(String status, EventType filter) {
        if (filter == EventType.ALL || status == null) {
            return true;
        }
        var st = status.toUpperCase(Locale.ROOT);
        return switch (filter) {
            case START -> "ACTIVE".equals(st);
            case STOP -> "SHUTOFF".equals(st);
            case PAUSE -> "PAUSED".equals(st);
            case SUSPEND -> "SUSPENDED".equals(st);
            case DELETE -> "DELETED".equals(st);
            default -> false;
        };
    }

    @Getter
    @Builder
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServerEvent {
        @Schema(
            title = "Server UUID",
            description = """
                The unique identifier of the server.
                """
        )
        private String serverId;

        @Schema(
            title = "Server Name",
            description = """
                Human-readable name of the server instance.
                """
        )
        private String serverName;

        @Schema(
            title = "Event Type",
            description = """
                The event or action name triggered (e.g. start, stop, reboot, SHUTOFF).
                """
        )
        private String eventType;

        @Schema(
            title = "Action Request ID",
            description = """
                Unique request ID of the instance action in Nova, if available.
                """
        )
        private String requestId;

        @Schema(
            title = "Event Timestamp",
            description = """
                ISO 8601 timestamp of when the event occurred.
                """
        )
        private String timestamp;
    }

    @Getter
    @Builder
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Detected server events",
            description = """
                List of compact server lifecycle events that triggered the execution.
                """
        )
        private final List<ServerEvent> events;

        @Schema(
            title = "Event count",
            description = """
                The number of server events detected.
                """
        )
        private final int size;
    }
}

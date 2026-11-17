package io.kestra.plugin.openstack.client.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Type of server lifecycle event to trigger on")
public enum EventType {
    ALL,
    CREATE,
    START,
    STOP,
    REBOOT,
    PAUSE,
    SUSPEND,
    RESUME,
    DELETE,
    RESIZE,
    REBUILD
}

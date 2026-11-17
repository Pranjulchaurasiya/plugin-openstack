package io.kestra.plugin.openstack.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Server {

    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("status")
    private String status;

    @JsonProperty("created")
    private String created;

    @JsonProperty("updated")
    private String updated;

    @JsonProperty("addresses")
    private Map<String, List<Address>> addresses;

    @JsonProperty("flavor")
    private Map<String, Object> flavor;

    @JsonProperty("image")
    private Map<String, Object> image;

    @JsonProperty("metadata")
    private Map<String, String> metadata;

    @JsonProperty("key_name")
    private String keyName;

    @JsonProperty("description")
    private String description;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("power_state")
    private Integer powerState;

    @JsonProperty("OS-EXT-STS:vm_state")
    private String vmState;

    @JsonProperty("OS-EXT-STS:task_state")
    private String taskState;

    @Getter
    @Builder
    @ToString
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        @JsonProperty("addr")
        private String addr;

        @JsonProperty("version")
        private Integer version;

        @JsonProperty("OS-EXT-IPS:type")
        private String type;

        @JsonProperty("OS-EXT-IPS-MAC:mac_addr")
        private String macAddr;
    }
}

package io.kestra.plugin.openstack.compute;

import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
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
public abstract class AbstractComputeTask extends Task {

    @NotNull
    @Schema(
        title = "Keystone authentication URL",
        description = """
            The Identity service endpoint URL for authentication, e.g. `https://keystone.example.com:5000/v3`.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> authUrl;

    @Schema(
        title = "Application Credential ID",
        description = """
            The OpenStack Keystone Application Credential ID for authentication.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> applicationCredentialId;

    @Schema(
        title = "Application Credential Secret",
        description = """
            The OpenStack Keystone Application Credential Secret for authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    protected Property<String> applicationCredentialSecret;

    @Schema(
        title = "OpenStack username",
        description = """
            The username for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> username;

    @Schema(
        title = "OpenStack password",
        description = """
            The password for OpenStack password-based authentication.
            """
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    protected Property<String> password;

    @Schema(
        title = "Target project name",
        description = """
            The OpenStack project/tenant name to scope authentication to when using password credentials.
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> projectName;

    @Schema(
        title = "User domain name",
        description = """
            The domain name for the user when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> userDomainName = Property.ofValue("Default");

    @Schema(
        title = "Project domain name",
        description = """
            The domain name for the project when using password credentials. Defaults to `Default`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> projectDomainName = Property.ofValue("Default");

    @Schema(
        title = "Region name",
        description = """
            The OpenStack region to target in the service catalog (e.g. `RegionOne`).
            """
    )
    @PluginProperty(group = "connection")
    protected Property<String> region;

    @Schema(
        title = "Endpoint interface type",
        description = """
            The endpoint interface type to resolve from the service catalog: `public`, `internal`, or `admin`. Defaults to `public`.
            """
    )
    @PluginProperty(group = "connection")
    @Builder.Default
    protected Property<String> endpointType = Property.ofValue("public");

    @Schema(
        title = "HTTP client options",
        description = """
            Advanced HTTP client configuration such as timeouts and proxy settings.
            """
    )
    @PluginProperty(group = "connection")
    protected HttpConfiguration options;

    protected OpenStackClient createClient(RunContext runContext) throws Exception {
        return OpenStackClient.builder()
            .runContext(runContext)
            .httpConfiguration(this.options)
            .authUrl(runContext.render(this.authUrl).as(String.class).orElse(null))
            .applicationCredentialId(runContext.render(this.applicationCredentialId).as(String.class).orElse(null))
            .applicationCredentialSecret(runContext.render(this.applicationCredentialSecret).as(String.class).orElse(null))
            .username(runContext.render(this.username).as(String.class).orElse(null))
            .password(runContext.render(this.password).as(String.class).orElse(null))
            .projectName(runContext.render(this.projectName).as(String.class).orElse(null))
            .userDomainName(runContext.render(this.userDomainName).as(String.class).orElse("Default"))
            .projectDomainName(runContext.render(this.projectDomainName).as(String.class).orElse("Default"))
            .region(runContext.render(this.region).as(String.class).orElse(null))
            .endpointType(runContext.render(this.endpointType).as(String.class).orElse("public"))
            .build();
    }
}

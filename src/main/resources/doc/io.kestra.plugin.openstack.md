# How to use the OpenStack plugin

Integrate with [OpenStack](https://www.openstack.org/) to orchestrate virtual machine lifecycle, power operations, metadata, snapshots, and event-driven automation across private and public OpenStack clouds (such as OVHcloud Public Cloud, Canonical OpenStack, Red Hat OpenStack Platform, etc.).

The OpenStack plugin provides tasks for [Nova Compute](https://docs.openstack.org/api-ref/compute/) and [Glance Image](https://docs.openstack.org/api-ref/image/v2/) services, authenticated via [Keystone v3](https://docs.openstack.org/api-ref/identity/v3/).

## Authentication

All tasks authenticate against the OpenStack Keystone Identity API (`/v3`) using either **Application Credentials** (recommended for automation and service accounts) or **User Credentials** (username, password, and project/domain scope).

Credentials can be passed via Kestra [secrets](https://kestra.io/docs/concepts/secret) or configured org-wide using [plugin defaults](https://kestra.io/docs/workflow-components/plugin-defaults).

### 1. Application Credentials (Recommended)

Application credentials provide scoped, non-expiring machine tokens without exposing primary user passwords:

```yaml
tasks:
  - id: list_servers
    type: io.kestra.plugin.openstack.compute.ListServers
    authUrl: "https://identity.region.openstack.example.com/v3"
    applicationCredentialId: "{{ secret('OS_APP_CRED_ID') }}"
    applicationCredentialSecret: "{{ secret('OS_APP_CRED_SECRET') }}"
```

### 2. Username and Password

```yaml
tasks:
  - id: list_servers
    type: io.kestra.plugin.openstack.compute.ListServers
    authUrl: "https://identity.region.openstack.example.com/v3"
    username: "kestra-operator"
    password: "{{ secret('OS_PASSWORD') }}"
    userDomainName: "Default"
    projectName: "production"
    projectDomainName: "Default"
    region: "RegionOne"
```

## Compute Tasks

### Virtual Machine Provisioning & Lifecycle

- **CreateServer**: Provision a new Nova VM instance.
  - **Required fields**: `name`, `flavorRef`, `imageRef`
  - **Optional fields**: `networks` (list of UUIDs), `keyName`, `userData`, `metadata`, `serverDescription`, `tags`
- **ListServers**: Query servers with regex filtering, status filters, and pagination markers.
  - **Optional fields**: `nameRegex`, `status` (`ACTIVE`, `SHUTOFF`, `ERROR`, etc.), `changesSince`, `limit`, `marker`
- **UpdateServer**: Update mutable attributes (name, description) of an existing server.
  - **Required fields**: `serverId`
  - **Optional fields**: `name`, `serverDescription`
- **DeleteServer**: Terminate and permanently delete a VM instance.
  - **Required fields**: `serverId`
- **GetServerMetadata**: Retrieve key-value metadata attached to a server.
  - **Required fields**: `serverId`

### Power & Instance State Management

- **StartServer**: Power on a stopped/shutoff server instance.
  - **Required fields**: `serverId`
- **StopServer**: Gracefully power off a running server instance.
  - **Required fields**: `serverId`
- **RebootServer**: Issue a soft or hard reboot to a server instance.
  - **Required fields**: `serverId`
  - **Optional fields**: `rebootType` (`SOFT` or `HARD`, defaults to `SOFT`)
- **ResetServer**: Force a hard reboot on a server instance.
  - **Required fields**: `serverId`
- **SuspendServer**: Suspend a server instance to hypervisor memory.
  - **Required fields**: `serverId`
- **ResumeServer**: Resume a suspended server instance.
  - **Required fields**: `serverId`

### Snapshots & Disaster Recovery

- **CreateServerSnapshot**: Create a point-in-time Glance snapshot image from an active server.
  - **Required fields**: `serverId`, `name`
  - **Optional fields**: `metadata`
- **ListServerSnapshots**: List all snapshot images belonging to an instance.
  - **Required fields**: `serverId`
- **DeleteServerSnapshot**: Permanently delete a snapshot image from Glance.
  - **Required fields**: `snapshotId`
- **RestoreServerFromSnapshot**: Rebuild an existing server instance from a snapshot image.
  - **Required fields**: `serverId`, `imageRef`
- **CloneServer**: Orchestrate automated server cloning by snapshotting a source server and launching a new instance from that snapshot.
  - **Required fields**: `sourceServerId`, `cloneName`, `flavorRef`
  - **Optional fields**: `networks`, `deleteSnapshotAfterClone` (defaults to `false`), `metadata`

## Trigger

- **Trigger**: Polling trigger that queries OpenStack Nova for servers matching status, name regex, and state changes, dispatching flow executions automatically.
  - **Properties**: `status`, `serverNameRegex`, `interval` (polling interval)

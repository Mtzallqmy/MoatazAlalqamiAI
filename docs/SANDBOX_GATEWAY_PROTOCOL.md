# Sandbox Gateway Protocol

This document specifies the REST contract that any Sandbox Gateway MUST implement
so that the app's `RemoteSandboxBackend` (commonMain) can operate against it —
whether the gateway runs on-device, in the user's own Incus farm, or in a
hosted environment. The phone never administers VMs directly; all management
flows through the gateway, which owns the admin plane and holds admin
credentials in a keystore.

## Authentication

All endpoints require `Authorization: Bearer <scoped-token>`. Tokens are minted
by the gateway's credential proxy from the app's long-lived refresh material.
The gateway MUST mint per-VM scoped tokens on request and log every operation.

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | /api/v1/vms | Create a VM (`distro`, `profile`, `network_policy`, `workspace_root`). Returns `{id, status}`. |
| GET | /api/v1/vms | List VMs visible to the caller only. |
| POST | /api/v1/vms/{id}/start | Start a VM. |
| POST | /api/v1/vms/{id}/stop | Stop a VM. |
| DELETE | /api/v1/vms/{id} | Destroy a VM. |
| POST | /api/v1/sandboxes/{id}/exec | Execute `command` + `args` with `working_directory`, `environment`, `timeout_seconds`, `stdin`, `pty`. Returns `{exit_code, stdout, stderr}`. |
| PUT | /api/v1/sandboxes/{id}/files/write | Write file (`path`, `content`). |
| GET | /api/v1/sandboxes/{id}/files/read?path=&max_length= | Read file as text. |
| GET | /api/v1/sandboxes/{id}/files?path=&recursive= | List files. |
| DELETE | /api/v1/sandboxes/{id}/files?path= | Delete a file. |
| PUT | /api/v1/sandboxes/{id}/files/move | Move/rename (`from`, `to`). |
| GET | /api/v1/sandboxes/{id}/processes | List processes. |
| POST | /api/v1/sandboxes/{id}/processes/{pid}/kill | Kill process (`signal`). |
| POST | /api/v1/sandboxes/{id}/ports | Expose a port (`port`, `protocol`). Returns `{port, protocol, proxy_url, expires_epoch_ms}`. Previews use `proxy_url` — never the raw VM IP. |
| DELETE | /api/v1/sandboxes/{id}/ports/{port} | Close an exposed port. |
| POST | /api/v1/sandboxes/{id}/snapshots | Snapshot (`label`). Returns `{id, label, created_epoch_ms, size_bytes}`. |

## Status codes

- 401/403 → `SandboxError.AuthError`
- 404 → `SandboxError.SandboxUnavailable`
- 429 → `SandboxError.RateLimitError`
- 5xx → `SandboxError.ProviderUnavailable`

## Incus provider reference

An Incus implementation creates LXD/Incus VMs from a cloud image, attaches the
requested `ResourceProfile` (Light 1vCPU/2GB, Standard 2/4, Build 4/8), applies
the VM's network policy via profile/device config, and enforces quotas
(max 4 concurrent VMs, 50 GiB disk, 16 GiB RAM, optional max lifetime).

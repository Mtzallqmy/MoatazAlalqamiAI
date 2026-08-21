# Moataz Beta threat model

## Scope and trust boundaries

The Android app, APK-embedded Full runtime, and user-owned `/workspace` are the
local trust boundary. Runtime catalogs, extension catalogs, model providers,
Git remotes, and the experimental Sandbox Gateway are untrusted network
boundaries. Infrastructure administration credentials never belong in the APK.

| Asset | Primary threats | Required controls |
|---|---|---|
| Projects | traversal, symlink escape, destructive agent actions | `/workspace` confinement, archive limits, explicit destructive approval, snapshot/diff/undo |
| API tokens | logs, fallback disclosure, extension access | encrypted secret store, redaction, provider-transfer approval, scoped extension grants |
| Runtime bundle | mirror compromise, downgrade, partial activation | Ed25519 envelope, pinned key ID, SHA-256, monotonic release policy, inactive slot, health probe, atomic activation |
| Extensions | mutable source, permission escalation, poisoned update | immutable source, manifest digest, integrity verification, exact-version grants, health check, rollback |
| Remote sandbox | IDOR, stolen JWT, output exhaustion, cross-tenant access | short JWT, tenant-scoped lookup, limits/rate limiting, audit metadata, TLS, cancellation |
| Telemetry | source/prompt/secret disclosure | separate opt-in flags, local aggregation, redaction, bounded exporter |

## Fail-closed rules

- Unsigned remote configuration, catalogs, runtime releases, and unknown signing keys are rejected.
- Experimental cloud flags default to `false`; disabled means no background connection.
- Cross-provider fallback does not transmit conversation data without approval.
- An extension receives no permission without an exact ID, version, and manifest-digest grant.
- A runtime cannot become active before architecture, distro, shell, filesystem, CLI, PRoot, and PTY health checks.
- Push, deployment, deletion, package installation, and external effects require explicit approval.

## Known Beta gaps

- The Incus provider and production identity service are not implemented or validated.
- Key provisioning and rotation require a protected release pipeline; development keys are not production trust roots.
- Device-farm, penetration, and restore-drill results are required before a Production label.
- Optional encrypted sync, team workspaces, and cloud audit export remain disabled until server implementations and deletion/retention tests exist.

## Release security gate

A Beta release requires green unit/build checks, Full/Lite APK inspection,
signed release artifacts, an SBOM and license report, secret scanning, and a
recorded rollback target. A Production release additionally requires ARM64
device tests, tenant-isolation tests against the deployed gateway, key-rotation
and backup-restore drills, rate-limit tests, and an external security review.

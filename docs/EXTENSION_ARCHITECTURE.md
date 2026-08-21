# Extension platform

Moataz uses one manifest and admission path for CLI, MCP, and Skill extensions.
Terminal and Agent core consume capabilities through adapters and never parse a
provider-specific extension package.

## Install transaction

1. Validate schema, immutable source, compatibility, and health contract.
2. Require a grant for every requested permission. The grant is bound to the
   extension ID, exact version, and canonical manifest SHA-256.
3. Fetch through the source adapter and verify artifact integrity/signature.
4. Stage outside the active revision.
5. Run the declared health probe within granted permissions.
6. Activate atomically; rollback the staged revision on failure or cancellation.

Supported source identities are APK built-ins, signed catalog artifacts, a full
Git commit SHA, and immutable local imports. Branch names such as `main` are not
valid identities.

## Permissions

Permissions are deny-by-default: workspace read/write, process execution,
terminal control, network, package installation, secret use, and external
effect. An update that changes version, digest, or requested permissions needs a
new decision. A manifest declaration alone is never authorization.

## Beta status

The versioned manifest, permission policy, staged installer orchestration,
health gate, and rollback contracts are implemented and unit-tested. Persistent
catalog storage, user-facing permission review, MCP network sandboxing, and a
signed public catalog remain disabled and are release blockers for remote
extension distribution.

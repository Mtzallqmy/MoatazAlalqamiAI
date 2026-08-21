# Backup and disaster recovery

Local-first operation remains available during cloud outage. Optional sync and
backup never include API keys, private keys, or infrastructure credentials.
Client-side encrypted envelopes require version, algorithm, key derivation
metadata, nonce, ciphertext, authentication tag, record ID, and conflict ID;
the server must not receive the user encryption key.

For a future Sandbox Gateway deployment, back up encrypted database and object
storage to a separate failure domain, version signing keys, document revocation,
and retain signed Runtime manifests and artifacts. Define RPO/RTO per service,
then validate them with scheduled restore drills. A backup is not considered
successful until a restore into an isolated environment passes integrity and
tenant-isolation checks.

Incident order: disable affected feature flag, stop staged rollout, revoke
compromised tokens/keys, preserve redacted audit evidence, restore or rollback,
validate isolation and integrity, then re-enable a limited cohort. Cloud failure
must not block Full/Offline Runtime or access to existing local projects.

This document is the required contract; no deployed backup or restore drill is
claimed by the current repository.

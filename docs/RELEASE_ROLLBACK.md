# Release and rollback plan

## Independent versions

- App: APK version name/code and compatibility IDs.
- Runtime: independently released execution contract.
- RootFS: Debian build identity and content hash.
- CLI bundle: versions of embedded developer tools such as OpenCode.

Full/Offline embeds the verified Debian payload. Lite contains no rootfs and may
download only a signed, compatible release from an allowlisted HTTPS host.

## Runtime rollout

Rollout eligibility is deterministic on-device using a stable installation ID,
rollout salt, and basis points; the installation ID is not uploaded for cohort
selection. Downloaded content is resumable, size-bounded, and SHA-256 verified.
Installation targets the inactive A/B slot. Activation occurs only after health
checks, while `/workspace` remains outside both slots.

Rollback switches atomically to the previous verified slot. Failed candidates
are never marked Ready. Revocation, downgrade protection, signing-key rotation,
and cohort changes must be delivered in signed metadata.

## APK rollback

Before rollout, record the previous APK and Runtime release IDs and retain their
artifacts. Stop rollout through a signed kill switch, rollback Runtime first when
compatible, then publish the previous APK with a higher `versionCode` if an app
rollback is required. Never delete projects or encrypted settings.

The current CI validates ephemeral CI signing only. Production signing and
store staged rollout are external protected release steps and are not claimed
as completed by repository CI.

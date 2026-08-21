# Moataz Runtime Architecture

## Production contract

- Debian 13 Trixie
- `arm64`
- one rootfs shared by local chat tools, Moataz Code, terminal sessions, and agents
- `/workspace` is the canonical guest project root
- `/root/projects` binds the same host directory for upgrade compatibility

The embedded rootfs is selected only when its JSON manifest matches distro,
major version, codename, architecture, required CLI contract, and SHA-256. It is
extracted into `rootfs.staging`, checked for cancellation, configured, then
renamed atomically. Before an existing installation is removed, the installer
checks the embedded manifest/parts and at least 1 GiB of free storage. A corrupt
embedded manifest fails closed instead of silently switching to a network
download. The install marker is written through a synced temporary file and an
atomic same-directory move only after all health probes succeed.

## Readiness

`EnvironmentDoctor` is authoritative. It verifies native PRoot libraries,
`/bin/sh`, `/bin/bash`, `os-release`, `dpkg --print-architecture`, required CLI,
writable filesystem locations, both workspace binds, a real PRoot boot, and a
guest PTY open/resize/input roundtrip with a non-empty `TERM`. The workspace probe
writes a nonce through `/workspace`, reads it back through `/root/projects`, then
repeats the operation in reverse. Only an empty issue list produces `Ready`.

The doctor-level PTY probe verifies the guest PTY facility. It is not a substitute
for an Android ARM64 device smoke test of the app's production terminal bridge,
SIGWINCH, interactive Ctrl+C, and cancellation; those require a real device or an
ARM64 emulator.

`EnvironmentRepairPlanner` converts concrete issues into targeted actions.
Missing packages, shell/usr-merge damage, native library copies, and mount points
are repaired in place. Boot, PTY, embedded-agent, and wrong
distro/version/architecture failures transition explicitly to a rootfs reinstall;
the projects host directory is outside the rootfs and is not deleted. A failed
health check or repair always transitions to a concrete error state.

Ubuntu and Alpine identifiers remain readable for stored settings and legacy
installs, but they are compatibility/experimental choices, not the production
local runtime, and never bypass the production readiness gate.

## Diagnostics and baseline

Runtime health probes, total installation duration, chat-shell startup, and repair
events are retained in a bounded process-wide diagnostic sink. Commands, stderr,
credential URLs, bearer tokens, common provider tokens, and private keys are
redacted before retention. Settings exposes **Copy runtime diagnostics**.

The checked-in Debian 13 rootfs occupies approximately **137.96 MiB** compressed
and contains 17 required developer CLI executables plus OpenCode. The rootfs
verifier prints the measured compressed size and its verification duration.
Installation and shell-start durations are measured on the actual user device,
not guessed from CI.

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
renamed atomically. A failure never writes the install marker.

## Readiness

`EnvironmentDoctor` is authoritative. It verifies native PRoot libraries,
`/bin/sh`, `/bin/bash`, `os-release`, `dpkg --print-architecture`, required CLI,
writable filesystem locations, both workspace binds, a real PRoot boot, and a
PTY open/resize/input roundtrip with a non-empty `TERM`. Only an empty issue list
produces `Ready`.

`EnvironmentRepairPlanner` converts concrete issues into targeted actions.
Missing packages, shell/usr-merge damage, native library copies, and mount points
are repaired in place. Wrong distro/version/architecture requests a rootfs
reinstall; the projects host directory is outside the rootfs and is not deleted.

Ubuntu and Alpine identifiers remain readable for stored settings and legacy
installs, but they are compatibility/experimental choices, not the production
local runtime.

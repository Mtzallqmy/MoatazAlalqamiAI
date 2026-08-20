#!/usr/bin/env bash
# Build the production Debian 13 arm64 rootfs embedded in the Android release.
#
# Requirements:
#   - Docker
#   - arm64 binfmt/QEMU when the host is not arm64
#   - xz
#
# Usage:
#   bash scripts/build-debian-rootfs.sh [output.tar.xz]

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT="${1:-$ROOT/androidApp/src/main/assets/moataz-debian-rootfs-arm64-v13.tar.xz}"
IMAGE="${DEBIAN_IMAGE:-debian:13-slim}"
CONTAINER="kai-debian13-arm64-${RANDOM}-$$"
TMP_OUTPUT="${OUTPUT}.tmp"

cleanup() {
  docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -f "$TMP_OUTPUT"
}
trap cleanup EXIT

command -v docker >/dev/null || { echo "docker is required" >&2; exit 1; }
command -v xz >/dev/null || { echo "xz is required" >&2; exit 1; }

mkdir -p "$(dirname "$OUTPUT")"

echo "[rootfs] pulling $IMAGE for linux/arm64"
docker pull --platform linux/arm64 "$IMAGE" >/dev/null

echo "[rootfs] creating Debian 13 arm64 CLI image"
docker create \
  --platform linux/arm64 \
  --name "$CONTAINER" \
  -e DEBIAN_FRONTEND=noninteractive \
  "$IMAGE" \
  /bin/bash -lc '
    set -euo pipefail
    apt-get update
    apt-get install -y --no-install-recommends \
      bash bash-completion busybox-static ca-certificates curl wget git nano less jq ripgrep \
      zip unzip tar xz-utils python3 coreutils findutils sed grep procps psmisc \
      openssh-client rsync file

    mkdir -p \
      /root/projects \
      /root/.local/bin \
      /root/.opencode/bin \
      /root/.grok/bin \
      /usr/local/bin \
      /etc/profile.d \
      /var/lib/apt/lists/partial \
      /var/cache/apt/archives/partial \
      /var/lib/dpkg/updates \
      /var/lib/dpkg/info \
      /var/lib/dpkg/alternatives \
      /run/lock \
      /tmp
    chmod 1777 /tmp

    # Real static shell fallback. Android extraction has historically lost
    # usr-merge symlinks on some devices; PRoot can still boot from sh.real.
    BUSYBOX="$(command -v busybox)"
    test -x "$BUSYBOX"
    cp "$BUSYBOX" /usr/bin/sh.real
    chmod 0755 /usr/bin/sh.real

    cat >/etc/profile.d/kai-build-path.sh <<"EOF"
# Managed by Kai Build.
export PATH="/root/.local/bin:/root/.grok/bin:/root/.opencode/bin${PATH:+:${PATH}}"
EOF

    # The current build shell started before profile.d existed, so set the same
    # PATH explicitly for the installer and the health probes below.
    export PATH="/root/.local/bin:/root/.grok/bin:/root/.opencode/bin:$PATH"

    # OpenCode is part of the production offline experience. Fail the rootfs
    # build rather than shipping an image that advertises an unavailable AI CLI.
    for attempt in 1 2 3; do
      if curl -fsSL --retry 3 --connect-timeout 20 https://opencode.ai/install | bash; then
        break
      fi
      if [ "$attempt" -eq 3 ]; then
        echo "OpenCode installation failed" >&2
        exit 1
      fi
      sleep $((attempt * 3))
    done

    if [ -x /root/.opencode/bin/opencode ]; then
      ln -sfn /root/.opencode/bin/opencode /root/.local/bin/opencode
    fi

    # Build-time health checks mirror the Android runtime checks.
    test "$(. /etc/os-release; printf %s "$ID")" = debian
    test "$(. /etc/os-release; printf %s "$VERSION_ID")" = 13
    test "$(dpkg --print-architecture)" = arm64
    test -x /usr/bin/sh.real
    for c in bash python3 git curl wget tar sha256sum ps pgrep pkill jq rg ssh rsync file opencode; do
      command -v "$c" >/dev/null 2>&1 || { echo "missing CLI: $c" >&2; exit 1; }
    done

    {
      echo "distribution=debian"
      echo "version=13"
      echo "codename=trixie"
      echo "architecture=arm64"
      echo "built_at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "opencode_version=$(opencode --version 2>/dev/null | head -1 || true)"
    } >/etc/kai-rootfs-release

    apt-get clean
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /tmp/* /var/tmp/*
  ' >/dev/null

docker start -a "$CONTAINER"

echo "[rootfs] exporting and compressing"
docker export "$CONTAINER" | xz -T0 -9e > "$TMP_OUTPUT"
xz -t "$TMP_OUTPUT"
mv "$TMP_OUTPUT" "$OUTPUT"

trap - EXIT
docker rm -f "$CONTAINER" >/dev/null

printf '[rootfs] ready: %s (%s)\n' "$OUTPUT" "$(du -h "$OUTPUT" | awk '{print $1}')"

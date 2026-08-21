#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets="$repo_root/androidApp/src/full/assets"
manifest="$assets/moataz-debian-rootfs-arm64.manifest.json"
legacy_asset="$assets/moataz-debian-rootfs-arm64.tar.xz"
work="$(mktemp -d)"
rootfs="$work/rootfs"
assembled="$work/base-rootfs.tar.xz"
output="$work/moataz-runtime.tar.xz"
opencode_version="${OPENCODE_VERSION:-1.18.19}"

cleanup() {
  sudo umount "$rootfs/dev/pts" 2>/dev/null || true
  sudo umount "$rootfs/dev" 2>/dev/null || true
  sudo umount "$rootfs/proc" 2>/dev/null || true
  sudo rm -rf "$work"
}
trap cleanup EXIT

mkdir -p "$rootfs"
if [[ -f "$legacy_asset" ]]; then
  cp "$legacy_asset" "$assembled"
else
  python3 - "$assets" "$manifest" "$assembled" <<'PY'
import json, pathlib, sys
assets, manifest_path, output = map(pathlib.Path, sys.argv[1:])
manifest = json.loads(manifest_path.read_text())
with output.open("wb") as target:
    for part in manifest["assetParts"]:
        target.write((assets / part["name"]).read_bytes())
PY
fi

sudo tar -xJpf "$assembled" -C "$rootfs"
sudo mkdir -p "$rootfs/proc" "$rootfs/dev/pts" "$rootfs/workspace" "$rootfs/root/projects" "$rootfs/root/.local/bin"
sudo mount -t proc proc "$rootfs/proc"
sudo mount --bind /dev "$rootfs/dev"
sudo mount --bind /dev/pts "$rootfs/dev/pts"
sudo install -m 0755 /usr/bin/qemu-aarch64-static "$rootfs/usr/bin/qemu-aarch64-static"
# Linux container images commonly make this an absolute symlink. Following it
# from the host can resolve back to the host file and make cp reject a same-file
# copy, so replace the guest link with an ordinary runtime-local file.
if [[ -e "$rootfs/etc/resolv.conf" || -L "$rootfs/etc/resolv.conf" ]]; then
  sudo unlink "$rootfs/etc/resolv.conf"
fi
sudo cp /etc/resolv.conf "$rootfs/etc/resolv.conf"
printf '#!/bin/sh\nexit 101\n' | sudo tee "$rootfs/usr/sbin/policy-rc.d" >/dev/null
sudo chmod 0755 "$rootfs/usr/sbin/policy-rc.d"

packages=(
  bash ca-certificates curl wget git nano less unzip python3 tar xz-utils
  coreutils procps jq ripgrep openssh-client rsync file
)
sudo chroot "$rootfs" /usr/bin/qemu-aarch64-static /bin/bash -ceu \
  "export DEBIAN_FRONTEND=noninteractive; apt-get update; apt-get install -y --no-install-recommends ${packages[*]}; test -z \"\$(dpkg --audit)\"; apt-get clean; rm -rf /var/lib/apt/lists/*"

release_json="$work/opencode-release.json"
curl --fail --location --retry 3 \
  "https://api.github.com/repos/anomalyco/opencode/releases/tags/v${opencode_version}" \
  --output "$release_json"
asset_name="opencode-linux-arm64.tar.gz"
asset_url="$(jq -er --arg name "$asset_name" '.assets[] | select(.name == $name) | .browser_download_url' "$release_json")"
asset_digest="$(jq -er --arg name "$asset_name" '.assets[] | select(.name == $name) | .digest' "$release_json")"
opencode_archive="$work/$asset_name"
curl --fail --location --retry 3 "$asset_url" --output "$opencode_archive"
printf '%s  %s\n' "${asset_digest#sha256:}" "$opencode_archive" | sha256sum --check -
mkdir -p "$work/opencode"
tar -xzf "$opencode_archive" -C "$work/opencode"
opencode_binary="$(find "$work/opencode" -type f -name opencode -print -quit)"
test -n "$opencode_binary"
sudo install -m 0755 "$opencode_binary" "$rootfs/usr/local/bin/opencode"

sudo tee "$rootfs/etc/profile.d/moataz-runtime.sh" >/dev/null <<'PROFILE'
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/root/.local/bin:/root/.opencode/bin
export MOATAZ_WORKSPACE=/workspace
PROFILE
sudo chmod 0644 "$rootfs/etc/profile.d/moataz-runtime.sh"
sudo ln -sfn /usr/local/bin/opencode "$rootfs/root/.local/bin/opencode"

sudo chroot "$rootfs" /usr/bin/qemu-aarch64-static /bin/bash -ceu '
  test "$(dpkg --print-architecture)" = arm64
  for tool in bash sh git curl wget tar xz python3 ps pgrep pkill jq rg ssh rsync file sha256sum; do command -v "$tool"; done
  opencode --version
  test -d /workspace && test -d /root/projects
'

sudo rm -f "$rootfs/usr/bin/qemu-aarch64-static" "$rootfs/usr/sbin/policy-rc.d"
sudo umount "$rootfs/dev/pts"
sudo umount "$rootfs/dev"
sudo umount "$rootfs/proc"

epoch="$(date -u -d '2026-08-20T00:00:00Z' +%s)"
sudo tar --sort=name --mtime="@$epoch" --owner=0 --group=0 --numeric-owner \
  -C "$rootfs" -cJf "$output" .

rm -f "$assets"/moataz-debian-rootfs-arm64.tar.xz.part-*
split -b 64m -d -a 2 "$output" "$assets/moataz-debian-rootfs-arm64.tar.xz.part-"
rm -f "$legacy_asset"

python3 - "$output" "$assets" "$manifest" "$opencode_version" <<'PY'
import datetime, hashlib, json, pathlib, sys
rootfs, assets, manifest_path = map(pathlib.Path, sys.argv[1:4])
opencode_version = sys.argv[4]
parts = []
for path in sorted(assets.glob("moataz-debian-rootfs-arm64.tar.xz.part-*")):
    data = path.read_bytes()
    parts.append({"name": path.name, "sha256": hashlib.sha256(data).hexdigest(), "sizeBytes": len(data)})
manifest = {
    "schemaVersion": 2,
    "distro": "debian",
    "version": "13",
    "codename": "trixie",
    "architecture": "arm64",
    "buildId": f"moataz-runtime-opencode-{opencode_version}",
    "sha256": hashlib.sha256(rootfs.read_bytes()).hexdigest(),
    "requiredCli": ["bash", "sh", "git", "curl", "wget", "tar", "xz", "python3", "ps", "pgrep", "pkill", "jq", "rg", "ssh", "rsync", "file", "sha256sum"],
    "createdAt": datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    "assetParts": parts,
    "embeddedCli": {"opencode": opencode_version},
}
manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
PY

python3 "$repo_root/scripts/verify_rootfs.py" "$assets" "$manifest"

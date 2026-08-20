#!/usr/bin/env python3
"""Fail closed when the embedded Moataz Runtime asset violates its manifest."""

import hashlib
import json
import pathlib
import sys
import tarfile


REQUIRED_CLI = {
    "bash", "sh", "git", "curl", "wget", "tar", "xz", "python3", "ps",
    "pgrep", "pkill", "jq", "rg", "ssh", "rsync", "file", "sha256sum",
}


def main() -> int:
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_rootfs.py ROOTFS MANIFEST")
    rootfs = pathlib.Path(sys.argv[1])
    manifest_path = pathlib.Path(sys.argv[2])
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    digest = hashlib.sha256(rootfs.read_bytes()).hexdigest()
    assert manifest["schemaVersion"] == 1
    assert manifest["distro"] == "debian"
    assert str(manifest["version"]).split(".", 1)[0] == "13"
    assert manifest["codename"] == "trixie"
    assert manifest["architecture"] == "arm64"
    assert digest == manifest["sha256"], (digest, manifest["sha256"])
    assert REQUIRED_CLI.issubset(set(manifest["requiredCli"]))
    with tarfile.open(rootfs, mode="r:xz") as archive:
        member = next((m for m in archive.getmembers() if m.name.lstrip("./") == "usr/lib/os-release"), None)
        assert member is not None
        stream = archive.extractfile(member)
        assert stream is not None
        os_release = stream.read().decode("utf-8")
    assert "ID=debian" in os_release
    assert 'VERSION_ID="13"' in os_release or "VERSION_ID=13" in os_release
    assert "VERSION_CODENAME=trixie" in os_release
    print(f"verified Debian 13 arm64 rootfs: {digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Fail closed when the embedded Moataz Runtime asset violates its manifest."""

import hashlib
import json
import posixpath
import pathlib
import re
import sys
import tarfile
import tempfile
import time


REQUIRED_CLI = {
    "bash", "sh", "git", "curl", "wget", "tar", "xz", "python3", "ps",
    "pgrep", "pkill", "jq", "rg", "ssh", "rsync", "file", "sha256sum",
}

CLI_SEARCH_DIRS = ("usr/local/bin", "usr/bin", "bin", "usr/local/sbin", "usr/sbin", "sbin")
HASH_BUFFER_SIZE = 1024 * 1024


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(HASH_BUFFER_SIZE):
            digest.update(chunk)
    return digest.hexdigest()


def normalized_member_name(name: str) -> str:
    raw = name
    while raw.startswith("./"):
        raw = raw[2:]
    if raw in ("", "."):
        return ""
    assert not raw.startswith("/"), f"absolute tar member path: {name}"
    normalized = posixpath.normpath(raw)
    assert normalized != ".." and not normalized.startswith("../"), name
    return normalized


def resolved_executable(members: dict[str, tarfile.TarInfo], path: str) -> str | None:
    """Resolve an in-archive executable without trusting host filesystem links."""
    current = normalized_member_name(path)
    visited: set[str] = set()
    for _ in range(32):
        if current in visited:
            return None
        visited.add(current)
        member = members.get(current)
        if member is None:
            return None
        if member.isfile():
            return current if member.mode & 0o111 else None
        if member.issym():
            target = member.linkname
            current = normalized_member_name(
                target.lstrip("/") if target.startswith("/") else posixpath.join(posixpath.dirname(current), target)
            )
            continue
        if member.islnk():
            # Tar hard-link targets are archive-root relative.
            current = normalized_member_name(member.linkname)
            continue
        return None
    return None


def verify_cli_members(members: dict[str, tarfile.TarInfo], required_cli: set[str]) -> dict[str, str]:
    resolved: dict[str, str] = {}
    for executable in sorted(required_cli):
        for directory in CLI_SEARCH_DIRS:
            path = f"{directory}/{executable}"
            target = resolved_executable(members, path)
            if target is not None:
                resolved[executable] = target
                break
        assert executable in resolved, f"required CLI is absent or not executable: {executable}"
    return resolved


def validate_asset_parts(parts: object) -> list[dict]:
    assert isinstance(parts, list) and parts, "rootfs asset parts are missing"
    names = [part["name"] for part in parts]
    assert len(names) == len(set(names)), "duplicate rootfs asset part"
    assert all(
        isinstance(name, str)
        and name not in ("", ".", "..")
        and pathlib.PurePosixPath(name).name == name
        and "\\" not in name
        for name in names
    ), "unsafe asset part name"
    assert all(isinstance(part["sizeBytes"], int) and part["sizeBytes"] > 0 for part in parts), (
        "invalid asset part size"
    )
    assert all(re.fullmatch(r"[0-9a-f]{64}", part["sha256"]) for part in parts), (
        "invalid asset part SHA-256"
    )
    return parts


def main() -> int:
    started = time.perf_counter()
    if len(sys.argv) != 3:
        raise SystemExit("usage: verify_rootfs.py ROOTFS_OR_ASSET_DIR MANIFEST")
    source = pathlib.Path(sys.argv[1])
    manifest_path = pathlib.Path(sys.argv[2])
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    assert manifest["schemaVersion"] == 2
    assert manifest["distro"] == "debian"
    assert str(manifest["version"]).split(".", 1)[0] == "13"
    assert manifest["codename"] == "trixie"
    assert manifest["architecture"] == "arm64"
    assert REQUIRED_CLI.issubset(set(manifest["requiredCli"]))
    parts = validate_asset_parts(manifest["assetParts"])
    assert manifest.get("embeddedCli", {}).get("opencode")

    temporary = None
    try:
        if source.is_dir():
            temporary = tempfile.NamedTemporaryFile(suffix=".tar.xz", delete=False)
            rootfs = pathlib.Path(temporary.name)
            try:
                for part in parts:
                    part_path = source / part["name"]
                    assert part_path.is_file(), part_path
                    assert part_path.stat().st_size == part["sizeBytes"], part_path
                    assert sha256_file(part_path) == part["sha256"], part_path
                    with part_path.open("rb") as part_stream:
                        while chunk := part_stream.read(HASH_BUFFER_SIZE):
                            temporary.write(chunk)
                temporary.close()
            except BaseException:
                temporary.close()
                raise
        else:
            rootfs = source

        compressed_size = rootfs.stat().st_size
        assert compressed_size == sum(part["sizeBytes"] for part in parts), "combined asset size mismatch"
        digest = sha256_file(rootfs)
        assert digest == manifest["sha256"], (digest, manifest["sha256"])
        with tarfile.open(rootfs, mode="r:xz") as archive:
            named_members = [
                (normalized_member_name(member.name), member)
                for member in archive.getmembers()
            ]
            named_members = [(name, member) for name, member in named_members if name]
            members = dict(named_members)
            assert len(members) == len(named_members), "duplicate normalized tar member path"
            member = members.get("usr/lib/os-release") or members.get("etc/os-release")
            assert member is not None and member.isfile()
            stream = archive.extractfile(member)
            assert stream is not None
            os_release = stream.read().decode("utf-8")
            resolved_cli = verify_cli_members(members, REQUIRED_CLI)
            opencode = resolved_executable(members, "usr/local/bin/opencode")
            assert opencode is not None, "embedded OpenCode is absent or not executable"
        assert "ID=debian" in os_release
        assert 'VERSION_ID="13"' in os_release or "VERSION_ID=13" in os_release
        assert "VERSION_CODENAME=trixie" in os_release
        elapsed = time.perf_counter() - started
        size_mib = compressed_size / (1024 * 1024)
        print(
            f"verified Debian 13 arm64 rootfs: sha256={digest} "
            f"compressed_mib={size_mib:.2f} cli={len(resolved_cli)} verify_seconds={elapsed:.3f}"
        )
        return 0
    finally:
        if temporary is not None:
            pathlib.Path(temporary.name).unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())

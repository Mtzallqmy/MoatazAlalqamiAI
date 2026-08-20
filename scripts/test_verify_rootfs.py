#!/usr/bin/env python3
"""Regression coverage for the fail-closed embedded-runtime verifier."""

import tarfile
import unittest

from scripts.verify_rootfs import (
    normalized_member_name,
    resolved_executable,
    validate_asset_parts,
    verify_cli_members,
)


def tar_member(name: str, *, mode: int = 0o755, link: str | None = None, hard: bool = False) -> tarfile.TarInfo:
    member = tarfile.TarInfo(name)
    member.mode = mode
    if link is None:
        member.type = tarfile.REGTYPE
        member.size = 1
    else:
        member.type = tarfile.LNKTYPE if hard else tarfile.SYMTYPE
        member.linkname = link
    return member


def asset_part(name: str = "rootfs.part-00", **overrides: object) -> dict:
    return {"name": name, "sizeBytes": 64, "sha256": "a" * 64, **overrides}


class TarPathNormalizationTest(unittest.TestCase):
    def test_preserves_hidden_names_and_normalizes_relative_paths(self) -> None:
        self.assertEqual(normalized_member_name("./usr/bin/../bin/.tool"), "usr/bin/.tool")
        self.assertEqual(normalized_member_name("."), "")

    def test_rejects_absolute_paths(self) -> None:
        with self.assertRaisesRegex(AssertionError, "absolute"):
            normalized_member_name("/etc/shadow")

    def test_rejects_parent_traversal(self) -> None:
        for path in ("../outside", "usr/../../outside"):
            with self.subTest(path=path), self.assertRaises(AssertionError):
                normalized_member_name(path)


class ExecutableResolutionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.members = {
            "usr/bin/real": tar_member("usr/bin/real"),
            "usr/bin/tool": tar_member("usr/bin/tool", link="real"),
            "usr/bin/absolute": tar_member("usr/bin/absolute", link="/usr/bin/real"),
            "usr/bin/hard": tar_member("usr/bin/hard", link="usr/bin/real", hard=True),
            "usr/bin/noexec": tar_member("usr/bin/noexec", mode=0o644),
            "usr/bin/loop-a": tar_member("usr/bin/loop-a", link="loop-b"),
            "usr/bin/loop-b": tar_member("usr/bin/loop-b", link="loop-a"),
        }

    def test_resolves_relative_symlink_to_real_executable(self) -> None:
        self.assertEqual(resolved_executable(self.members, "usr/bin/tool"), "usr/bin/real")

    def test_resolves_guest_absolute_symlink_without_host_filesystem(self) -> None:
        self.assertEqual(resolved_executable(self.members, "usr/bin/absolute"), "usr/bin/real")

    def test_resolves_root_relative_hardlink(self) -> None:
        self.assertEqual(resolved_executable(self.members, "usr/bin/hard"), "usr/bin/real")

    def test_rejects_non_executable_regular_file(self) -> None:
        self.assertIsNone(resolved_executable(self.members, "usr/bin/noexec"))

    def test_rejects_missing_executable(self) -> None:
        self.assertIsNone(resolved_executable(self.members, "usr/bin/missing"))

    def test_rejects_symlink_cycles(self) -> None:
        self.assertIsNone(resolved_executable(self.members, "usr/bin/loop-a"))

    def test_rejects_symlink_that_escapes_archive(self) -> None:
        self.members["usr/bin/escape"] = tar_member("usr/bin/escape", link="../../../secret")
        with self.assertRaises(AssertionError):
            resolved_executable(self.members, "usr/bin/escape")

    def test_cli_lookup_accepts_real_resolved_executable(self) -> None:
        self.assertEqual(verify_cli_members(self.members, {"tool"}), {"tool": "usr/bin/real"})

    def test_cli_lookup_rejects_missing_executable(self) -> None:
        with self.assertRaisesRegex(AssertionError, "missing"):
            verify_cli_members(self.members, {"missing"})

    def test_cli_lookup_rejects_non_executable_file(self) -> None:
        with self.assertRaisesRegex(AssertionError, "noexec"):
            verify_cli_members(self.members, {"noexec"})


class AssetPartMetadataTest(unittest.TestCase):
    def test_accepts_distinct_well_formed_parts(self) -> None:
        parts = [asset_part(), asset_part("rootfs.part-01")]
        self.assertEqual(validate_asset_parts(parts), parts)

    def test_rejects_empty_part_list(self) -> None:
        with self.assertRaisesRegex(AssertionError, "missing"):
            validate_asset_parts([])

    def test_rejects_duplicate_part_names(self) -> None:
        with self.assertRaisesRegex(AssertionError, "duplicate"):
            validate_asset_parts([asset_part(), asset_part()])

    def test_rejects_unsafe_part_names(self) -> None:
        for name in ("../rootfs.part-00", "/rootfs.part-00", "nested/rootfs.part-00", "..", "", "a\\b"):
            with self.subTest(name=name), self.assertRaisesRegex(AssertionError, "unsafe"):
                validate_asset_parts([asset_part(name)])

    def test_rejects_zero_or_negative_sizes(self) -> None:
        for size in (0, -1):
            with self.subTest(size=size), self.assertRaisesRegex(AssertionError, "size"):
                validate_asset_parts([asset_part(sizeBytes=size)])

    def test_rejects_invalid_sha256(self) -> None:
        for digest in ("a" * 63, "g" * 64, "A" * 64):
            with self.subTest(digest=digest), self.assertRaisesRegex(AssertionError, "SHA-256"):
                validate_asset_parts([asset_part(sha256=digest)])


if __name__ == "__main__":
    unittest.main()

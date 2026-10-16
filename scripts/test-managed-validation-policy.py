#!/usr/bin/env python3
"""Consumer contract: an App artifact must also be admissible by managed validation.

This is a versioned data contract, not a dependency on Platform implementation.
The installed worker still checks its own root-owned policy before execution.
"""
import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POLICY = ROOT / "runtime-contract/atenea-android-inputs-v2.json"


def normalized(value):
    for pattern, replacement in (
        (rb"(?m)^        versionCode = [1-9][0-9]{0,8}$", b"        versionCode = 1"),
        (rb'(?m)^        versionName = "[0-9]{1,4}\.[0-9]{1,6}\.[0-9]{1,6}"$', b'        versionName = "0.0.0"'),
    ):
        value, count = re.subn(pattern, replacement, value)
        if count != 1: return None
    return value


def accepts(root, policy):
    roots = ("android", "android/app", "android/api", "android/secure", "android/core-console", "android/voice-runtime")
    forbidden = [root / base / name for base in roots for name in
                 ("build.gradle", "settings.gradle", "buildSrc", "gradle.lockfile")]
    forbidden += [root / "android/gradle" / name for name in ("verification-metadata.xml", "dependency-locks")]
    if any(path.exists() or path.is_symlink() for path in forbidden):
        return False
    for bundle in policy["bundles"]:
        for name, digest in {**policy["common"], **bundle["files"]}.items():
            path = root / name
            if any(p.is_symlink() for p in (path, *path.parents)) or not path.is_file(): break
            value = path.read_bytes()
            if name == "android/app/build.gradle.kts": value = normalized(value)
            if value is None or hashlib.sha256(value).hexdigest() != digest: break
        else: return True
    return False


class ManagedValidationConsumerContractTest(unittest.TestCase):
    def test_committed_application_is_admissible_by_versioned_managed_policy(self):
        self.assertTrue(accepts(ROOT, json.loads(POLICY.read_bytes())),
            "App changed a managed build input without a reviewed, coordinated Platform policy. Do not publish this artifact.")

    def test_product_version_bumps_are_not_dependency_changes(self):
        value=(ROOT/"android/app/build.gradle.kts").read_bytes()
        other=re.sub(rb'(?m)^        versionCode = [0-9]+$', b'        versionCode = 999',value)
        other=re.sub(rb'(?m)^        versionName = "[0-9.]+"$', b'        versionName = "0.5.999"',other)
        self.assertEqual(normalized(value), normalized(other))

    def test_ci_derived_or_executable_versions_are_rejected(self):
        value=(ROOT/"android/app/build.gradle.kts").read_bytes()
        for replacement in (b'stableBuild?.let { 1_000_000 + it } ?: 157', b'Runtime.getRuntime().exec("id")'):
            candidate=re.sub(rb'(?m)^(        versionCode = ).*$', lambda m:m[1]+replacement,value)
            self.assertIsNone(normalized(candidate))

    def test_other_gradle_changes_stay_hash_locked(self):
        value=(ROOT/"android/app/build.gradle.kts").read_bytes()
        self.assertNotEqual(normalized(value), normalized(value+b'\n// an unreviewed change\n'))

    def test_unreviewed_build_authorities_are_rejected(self):
        with tempfile.TemporaryDirectory(prefix="managed-policy-test-") as directory:
            root = Path(directory)
            policy = {"common": {}, "bundles": [{"files": {}}]}
            self.assertTrue(accepts(root, policy))
            for name in ("android/buildSrc", "android/app/build.gradle", "android/api/gradle.lockfile",
                         "android/gradle/dependency-locks", "android/gradle/verification-metadata.xml"):
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(b"unreviewed")
                self.assertFalse(accepts(root, policy), name)
                path.unlink()
            link = root / "android/buildSrc"
            link.symlink_to(root / "absent")
            self.assertFalse(accepts(root, policy))


if __name__ == "__main__": unittest.main()

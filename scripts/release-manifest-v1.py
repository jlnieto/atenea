#!/usr/bin/env python3
"""Fixed CI artifact manifest, never a deployment or signing operation."""
import hashlib
import json
import re
import subprocess
import sys
from pathlib import Path


def manifest(target):
    if target not in {"APP_PROD", "ANDROID_STABLE"}:
        raise ValueError("Unsupported fixed target")
    source = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if not re.fullmatch("[0-9a-f]{40}", source):
        raise ValueError("Invalid source identity")
    directory, payload = ("target/release-app", "image.tar") if target == "APP_PROD" else ("target/release-android", "app-unsigned.apk")
    directory = Path(directory)
    sha = hashlib.sha256()
    with (directory / payload).open("rb") as stream:
        for block in iter(lambda: stream.read(1024**2), b""):
            sha.update(block)
    code = name = None
    if target == "ANDROID_STABLE":
        gradle = Path("android/app/build.gradle.kts").read_text()
        code = int(re.search(r"^\s*versionCode = ([0-9]+)$", gradle, re.M)[1])
        name = re.search(r'^\s*versionName = "([0-9]+\.[0-9]+\.[0-9]+)"$', gradle, re.M)[1]
    return {"protocol": "atenea-release/v1", "target": target, "sourceCommit": source,
            "payloadSha256": sha.hexdigest(), "versionCode": code, "versionName": name,
            "flywayVersion": max(int(re.match(r"V([0-9]+)__", path.name)[1])
                for path in Path("src/main/resources/db/migration").glob("V*__*.sql")) if target == "APP_PROD" else None}


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(2)
    value = manifest(sys.argv[1])
    directory = Path("target/release-app" if sys.argv[1] == "APP_PROD" else "target/release-android")
    (directory / "manifest.json").write_text(json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n")

#!/usr/bin/env python3
"""Exact Beautips identity adapter for the accepted project Codex sandbox."""

from __future__ import annotations

import hashlib
import importlib.util
import sys
from pathlib import Path

BASE_RUNNER_SHA256 = "98ccaa001065d33b2645590c4f03549fcb41e301f6dfb028b8c57fe7276901f0"
BASE_PATH = Path(__file__).resolve().with_name("project-codex-runner-v1.py")

try:
    digest = hashlib.sha256(BASE_PATH.read_bytes()).hexdigest()
except OSError:
    print("project configuration rejected", file=sys.stderr)
    raise SystemExit(2)
if digest != BASE_RUNNER_SHA256:
    print("project configuration rejected", file=sys.stderr)
    raise SystemExit(2)

SPEC = importlib.util.spec_from_file_location("atenea_project_codex_base_v1", BASE_PATH)
if SPEC is None or SPEC.loader is None:
    print("project configuration rejected", file=sys.stderr)
    raise SystemExit(2)
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)

# Only immutable project identity changes. Execution, validation, cgroup,
# Bubblewrap, network denial, timeout, cancellation and result handling remain
# the hash-pinned accepted implementation above.
BASE.PROJECT_ID = "beautips"
BASE.REPOSITORY = "https://github.com/jlnieto/beautips.git"
BASE.BRANCH = "main"
BASE.BASE_COMMIT = "e9e0b3c319c518363d4135f5378ebbddced96dfb"
BASE.MANIFEST_SHA256 = (
    "365f1c66c51c9018c2c6f48deddbaa619b4588cae2dd463dcd916cde884e2e82"
)
BASE.GIT_COMMON_DIR = Path("/srv/atenea/repositories/beautips.git")
# validate_config must bind the root-owned config to this adapter, not the base.
BASE.__file__ = str(Path(__file__).resolve())


if __name__ == "__main__":
    try:
        raise SystemExit(BASE.main())
    except SystemExit:
        raise
    except Exception as exception:
        BASE.reject(BASE.internal_failure_reason(exception))

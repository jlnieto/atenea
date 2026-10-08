#!/usr/bin/env python3
"""Closed main-owned UFD controller for already-published legacy change heads."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import uuid

EVENT = "atenea-ufd-owned-head-v1"
REPOSITORY = "jlnieto/atenea"
SHA = re.compile(r"[0-9a-f]{40}")
BRANCH = re.compile(r"atenea/change-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
HARNESS = ("scripts/validate-change", "scripts/ufd-install.py", "scripts/ufd-android.py", ".delivery/engine-lock.json")


def require(condition, code):
    if not condition:
        raise RuntimeError(code)


def request_id(head, branch):
    raw = (EVENT + "|" + REPOSITORY + "|" + branch + "|" + head).encode()
    return str(uuid.UUID(bytes=hashlib.md5(raw).digest(), version=3))


def closed_request(event, repository, ref, authority):
    require(repository == REPOSITORY and ref == "refs/heads/main", "controller-authority-invalid")
    require(event.get("action") == EVENT, "dispatch-event-invalid")
    payload = event.get("client_payload")
    require(isinstance(payload, dict) and set(payload) == {"requestId", "headSha", "headBranch", "authoritySha"}, "closed-request-required")
    require(all(isinstance(value, str) for value in payload.values()), "dispatch-identity-invalid")
    require(SHA.fullmatch(payload["headSha"]) and SHA.fullmatch(payload["authoritySha"]), "commit-identity-invalid")
    require(BRANCH.fullmatch(payload["headBranch"]), "branch-ownership-invalid")
    require(payload["authoritySha"] == authority, "controller-main-moved")
    require(payload["requestId"] == request_id(payload["headSha"], payload["headBranch"]), "request-identity-invalid")
    return payload


def remote_head(payload):
    # Fixed repository and endpoint. No client-selected URL, command or path.
    response = json.loads(subprocess.check_output([
        "gh", "api", "repos/" + REPOSITORY + "/git/ref/heads/" + payload["headBranch"]]))
    require(response.get("ref") == "refs/heads/" + payload["headBranch"]
            and response.get("object", {}).get("type") == "commit"
            and response.get("object", {}).get("sha") == payload["headSha"], "published-head-moved")


def git(source, *arguments):
    return subprocess.check_output(["git", "-C", str(source), *arguments])


def verify_source(source, payload):
    require(git(source, "rev-parse", "HEAD").decode().strip() == payload["headSha"], "checkout-head-mismatch")
    require(not git(source, "status", "--porcelain", "--untracked-files=all"), "checkout-not-clean")
    base = git(source, "merge-base", "HEAD", payload["authoritySha"]).decode().strip()
    require(SHA.fullmatch(base), "base-identity-invalid")
    # Legacy recovery cannot replace the committed UFD harness or select an
    # easier policy/engine. The ordinary runner still classifies from this base.
    for path in HARNESS:
        require(git(source, "show", base + ":" + path) == git(source, "show", "HEAD:" + path), "legacy-harness-changed:" + path)
    require(git(source, "show", base + ":.delivery/policy.json"), "base-policy-missing")
    return base


def main():
    require(sys.argv[1:] in (["request"], ["verify"], ["receipt"]), "closed-controller-action-required")
    require(os.environ.get("GITHUB_EVENT_NAME") == "repository_dispatch", "dispatch-event-invalid")
    payload = closed_request(json.loads(Path(os.environ["GITHUB_EVENT_PATH"]).read_text()),
                             os.environ["GITHUB_REPOSITORY"], os.environ["GITHUB_REF"], os.environ["GITHUB_SHA"])
    if sys.argv[1] == "request":
        remote_head(payload)
        with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
            output.write("headSha=" + payload["headSha"] + "\n")
        return
    source = Path("source")
    if sys.argv[1] == "verify":
        remote_head(payload)
        git(source, "remote", "add", "github", "https://github.com/" + REPOSITORY + ".git")
        git(source, "fetch", "github", "main")
        verify_source(source, payload)
        return
    # Evidence is supplementary to (never a replacement for) plan/result.json.
    context = json.loads((source / "target/ufd/context.json").read_text())
    require(context["head"] == payload["headSha"], "ufd-head-mismatch")
    result = json.loads((source / "target/ufd/result.json").read_text())
    require(result["state"] == "passed", "ufd-not-passed")
    (source / "target/ufd/dispatch-receipt.json").write_text(json.dumps({
        "schemaVersion": 1, **payload, "baseSha": context["base"],
        "workflowRunId": os.environ["GITHUB_RUN_ID"], "repository": REPOSITORY,
        "workflow": ".github/workflows/ufd-validation-v1.yml", "state": "PASSED"
    }, indent=2) + "\n")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, KeyError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)

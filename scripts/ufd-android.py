#!/usr/bin/env python3
"""Execute the tiny Atenea Android UFD pilot; classification stays in UFD."""
import argparse
from collections import Counter
import json
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

from contract_checks.validate import ContractError, digest, loads, require, validate_policy, validate_result
from delivery.cli import classify, snapshot_policy
from delivery.git import Git

POLICY = ".delivery/policy.json"
SELECTED_GROUP = "voice-speech-safety-test"
FULL_GROUP = "android-unit-full"


def write(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n")


def plan(base, head, wheel, output):
    git = Git(".")
    base, head = git.resolve(base), git.resolve(head)
    require(git.resolve("HEAD") == head, "checkout-head-mismatch")
    result, policy = classify(".", base, head, POLICY, runner="android-docker", engine_wheel=wheel)
    require(result["evaluation"]["state"] == "evaluated", "unevaluable-plan")
    output.mkdir(parents=True, exist_ok=True)
    write(output / "plan.json", result)
    write(output / "policy.json", policy)
    write(output / "context.json", {"base": base, "head": head, "wheel": str(Path(wheel).resolve())})
    print(json.dumps({"tier": result["tier"], "tests": result["blocking_tests"]["ids"],
                      "checks": result["required_checks"]["ids"]}))


def selected_groups(policy, groups):
    selectors = []
    for group in groups:
        definition = policy["test_groups"][group]
        require(definition["operation"] == "gradle-android", "unsupported-test-operation")
        require(definition["selectors"], "empty-selector")
        selectors.extend(definition["selectors"])
    return selectors


def report_counts(root):
    counts, skipped = Counter(), Counter()
    reports = sorted(root.glob("**/TEST-*.xml"))
    require(reports, "empty-test-report")
    for path in reports:
        raw = path.read_bytes()
        require(b"<!DOCTYPE" not in raw and b"<!ENTITY" not in raw, "unsafe-report")
        suite = ET.fromstring(raw)
        require(suite.tag == "testsuite", "unsupported-report")
        require(int(suite.attrib["failures"]) == 0 and int(suite.attrib["errors"]) == 0, "failed-tests")
        for case in suite.findall("testcase"):
            name = case.attrib.get("classname")
            require(bool(name), "missing-test-identity")
            if case.find("skipped") is None:
                counts[name] += 1
            else:
                skipped[name] += 1
    require(sum(counts.values()) > 0, "empty-test-report")
    return counts, skipped


def run(output):
    context = json.loads((output / "context.json").read_text())
    policy = loads((output / "policy.json").read_bytes())
    result = loads((output / "plan.json").read_bytes())
    validate_policy(policy)
    require(Git(".").resolve("HEAD") == context["head"], "checkout-head-mismatch")
    require(digest(policy) == result["policy_digest"], "policy-digest-mismatch")
    groups = result["blocking_tests"]["ids"]
    checks = result["required_checks"]["ids"]
    require(set(groups) <= {SELECTED_GROUP, FULL_GROUP}, "unsupported-test-group")
    require(set(checks) <= {"ufd-policy"}, "unsupported-check")
    executions = []
    if "ufd-policy" in checks:
        subprocess.run([sys.executable, "-I", "-c",
                        "from delivery.cli import policy_main; raise SystemExit(policy_main())",
                        "check", "--repo", ".", "--policy", POLICY, "--ref", "HEAD"], check=True)
        executions.append({"kind": "check", "id": "ufd-policy", "status": "passed", "exit_code": 0,
                           "selectors": [], "test_count": {"state": "unknown", "basis": "not_reported"},
                           "duration_ms": {"state": "unknown", "basis": "not_calibrated"}})
    selectors = selected_groups(policy, groups)
    for selector in selectors:
        require(selector["kind"] in ("class", "suite"), "unsupported-selector")
    if SELECTED_GROUP in groups:
        class_selectors = [selector for selector in selectors if selector["kind"] == "class"]
        require(len(class_selectors) == 1, "unsupported-selector")
        selected_class = class_selectors[0]["value"]
        source = Path("android/core-console/src/test/java") / (selected_class.replace(".", "/") + ".kt")
        require(source.is_file(), "selector-source-missing")
        report_root = Path("android/core-console/build/test-results")
        shutil.rmtree(report_root, ignore_errors=True)
        subprocess.run(["./scripts/android-build.sh", ":core-console:test", "--tests", selected_class], check=True)
        counts, skipped = report_counts(report_root)
        require(counts[selected_class] > 0 and skipped[selected_class] == 0, "empty-selector")
        executions.append({"kind": "test", "id": SELECTED_GROUP, "status": "passed", "exit_code": 0,
                           "selectors": [{"kind": "class", "value": selected_class, "matches": counts[selected_class]}],
                           "test_count": {"state": "known", "value": counts[selected_class], "basis": "gradle-junit-xml"},
                           "duration_ms": {"state": "unknown", "basis": "not_calibrated"}})
    if FULL_GROUP in groups:
        report_root = Path("android")
        for module in ("app", "api", "secure", "core-console", "voice-runtime"):
            shutil.rmtree(report_root / module / "build/test-results", ignore_errors=True)
        subprocess.run(["./scripts/android-build.sh", ":app:testDebugUnitTest", ":api:testDebugUnitTest",
                        ":secure:testDebugUnitTest", ":core-console:testDebugUnitTest", ":voice-runtime:testDebugUnitTest"], check=True)
        counts, _ = report_counts(report_root)
        executions.append({"kind": "test", "id": FULL_GROUP, "status": "passed", "exit_code": 0,
                           "selectors": [{"kind": "suite", "value": "android-unit-tests", "matches": sum(counts.values())}],
                           "test_count": {"state": "known", "value": sum(counts.values()), "basis": "gradle-junit-xml"},
                           "duration_ms": {"state": "unknown", "basis": "not_calibrated"}})
    evidence = {"schema_version": 1, "plan_digest": digest(result), "input_digest": result["input_digest"],
                "state": "passed", "executions": executions, "phases": [], "artifacts": [],
                "smoke": {"state": "not_applicable", "executed": []},
                "fallback": {"state": "not_used", "from_tier": result["tier"], "to_tier": result["tier"], "reason": "none"},
                "errors": []}
    validate_result(evidence, result, policy)
    write(output / "result.json", evidence)
    print(json.dumps({"state": "passed", "tier": result["tier"], "executions": [item["id"] for item in executions]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("plan", "run"))
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--wheel")
    parser.add_argument("--output", type=Path, default=Path("target/ufd"))
    args = parser.parse_args()
    try:
        if args.command == "plan":
            require(args.base and args.wheel, "missing-plan-input")
            plan(args.base, args.head, args.wheel, args.output)
        else:
            run(args.output)
    except ContractError as error:
        print(str(error), file=sys.stderr)
        sys.exit(2)

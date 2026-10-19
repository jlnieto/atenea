#!/usr/bin/env python3
"""Closed CI diagnostic: identities and failure locations, never log messages/values."""
import argparse
import json
import re
from pathlib import Path
import xml.etree.ElementTree as ET

IDENTITY = re.compile(r"[A-Za-z_$][A-Za-z0-9_$.]{0,240}")
LOCATION = re.compile(r"(?:^|/)(src/(?:test|main)/java/(?:[A-Za-z0-9_]+/)*[A-Za-z0-9_]+\.java):\[(\d{1,6}),\d+\]")


def diagnostic(source, target, log, reports):
    if not re.fullmatch(r"[0-9a-f]{40}", source) or target != "APP_PROD":
        raise ValueError("closed build identity required")
    # CI owns the log. Only an allowlisted structural projection leaves CI.
    content = log.read_bytes()[-4 * 1024 * 1024:].decode("utf8", "replace")
    failures = []
    for match in LOCATION.finditer(content):
        item = {"kind": "COMPILATION", "path": match[1], "line": int(match[2])}
        if item not in failures:
            failures.append(item)
    for path in sorted(reports.glob("TEST-*.xml"))[:300]:
        raw = path.read_bytes()
        if len(raw) > 4 * 1024 * 1024 or b"<!DOCTYPE" in raw or b"<!ENTITY" in raw:
            continue
        for case in ET.fromstring(raw).findall("testcase"):
            if case.find("failure") is None and case.find("error") is None:
                continue
            name, method = case.get("classname", ""), case.get("name", "")
            if IDENTITY.fullmatch(name) and IDENTITY.fullmatch(method):
                item = {"kind": "TEST", "testClass": name, "testMethod": method}
                if item not in failures:
                    failures.append(item)
    return {"protocol": "atenea-build-diagnostic/v1", "sourceCommit": source,
            "target": target, "failures": failures[:40]}


if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("source"); p.add_argument("target")
    p.add_argument("log", type=Path); p.add_argument("output", type=Path)
    args = p.parse_args()
    args.output.write_text(json.dumps(diagnostic(args.source, args.target, args.log,
        Path("target/surefire-reports")), sort_keys=True) + "\n")

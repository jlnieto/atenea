#!/usr/bin/env python3
import importlib.util
from pathlib import Path
import tempfile
import unittest
import json

spec=importlib.util.spec_from_file_location("diagnostic",Path(__file__).with_name("build-diagnostic-v1.py"))
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class DiagnosticTest(unittest.TestCase):
    def test_only_structural_compile_locations_leave_ci(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);log=root/"build.log"
            log.write_text("[ERROR] /workspace/repo/src/test/java/com/atenea/Test.java:[71,19] secret=value\n"
                           "some unrelated /etc/credentials private text\n")
            value=module.diagnostic("a"*40,"APP_PROD",log,root)
            self.assertEqual(value["failures"],[{"kind":"COMPILATION","path":"src/test/java/com/atenea/Test.java","line":71}])
            self.assertNotIn("secret",str(value));self.assertNotIn("credentials",str(value))

    def test_test_identity_drops_assertions_and_exception_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);log=root/"build.log";log.write_text("")
            (root/"TEST-synthetic.xml").write_text('<testsuite><testcase classname="com.atenea.Test" name="fails"><failure message="secret">private values</failure></testcase></testsuite>')
            self.assertEqual(module.diagnostic("a"*40,"APP_PROD",log,root)["failures"],
                             [{"kind":"TEST","testClass":"com.atenea.Test","testMethod":"fails"}])

    def test_merge_workflow_runs_full_backend_on_the_exact_preview_without_overrides(self):
        text=(Path(__file__).resolve().parents[1]/".github/workflows/backend-merge-validation-v1.yml").read_text()
        self.assertIn("pull_request:",text);self.assertNotIn("pull_request_target:",text)
        for expected in ("HEAD^1", "HEAD^2", "EXPECTED_BASE", "EXPECTED_HEAD", "./scripts/ci-backend-test.sh", "persist-credentials: false"):
            self.assertIn(expected,text)
        for forbidden in ("-DskipTests", "-Dtest=", "continue-on-error", "secrets."):
            self.assertNotIn(forbidden,text)

    def test_contract_is_closed_and_versioned(self):
        schema=json.loads((Path(__file__).resolve().parents[1]/"runtime-contract/atenea-build-diagnostic-v1.schema.json").read_text())
        self.assertEqual("https://json-schema.org/draft/2020-12/schema",schema["$schema"])
        self.assertFalse(schema["additionalProperties"])
        self.assertEqual("atenea-build-diagnostic/v1",schema["properties"]["protocol"]["const"])
        for shape in schema["properties"]["failures"]["items"]["oneOf"]:
            self.assertFalse(shape["additionalProperties"])


if __name__=="__main__": unittest.main()

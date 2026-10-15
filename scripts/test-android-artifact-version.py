#!/usr/bin/env python3
import json
import os
import re
import tempfile
import unittest
from pathlib import Path
from android_artifact_version import read_version

REPOSITORY = Path(__file__).resolve().parent.parent


class ReleaseVersionPolicyTest(unittest.TestCase):
    def test_committed_product_version_is_explicit_not_derived_from_ci(self):
        source=(REPOSITORY/'android/app/build.gradle.kts').read_text()
        code=re.findall(r'^\s*versionCode\s*=\s*([0-9]+)\s*$',source,re.MULTILINE)
        name=re.findall(r'^\s*versionName\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"\s*$',source,re.MULTILINE)
        self.assertEqual(1,len(code));self.assertEqual(1,len(name))
        self.assertTrue(0<int(code[0])<2_147_483_647)
        self.assertNotIn('ATENEA_STABLE_BUILD_NUMBER',source)

    def test_ci_and_local_build_do_not_override_product_version(self):
        workflow=(REPOSITORY/'.github/workflows/release-artifacts-v1.yml').read_text()
        script=(REPOSITORY/'scripts/android-build.sh').read_text()
        for source in (workflow,script):
            self.assertNotIn('ATENEA_STABLE_BUILD_NUMBER',source)
        self.assertIn('python3 -B scripts/test-android-artifact-version.py',workflow)


class ActualVersionTest(unittest.TestCase):
    def setUp(self):
        self.original=Path.cwd();tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup)
        os.chdir(tmp.name);self.addCleanup(os.chdir,self.original)
        self.path=Path("android/app/build/outputs/apk/release/output-metadata.json");self.path.parent.mkdir(parents=True)
        self.value={"applicationId":"com.atenea.android","elements":[{"versionCode":157,"versionName":"0.5.124"}]}
    def write(self):self.path.write_text(json.dumps(self.value))
    def test_actual_artifact_version_not_guessed_from_declared_source(self):
        self.write();self.assertEqual((157,"0.5.124"),read_version("release"))
    def test_ambiguous_apk_or_foreign_package_rejected(self):
        self.value["elements"]*=2;self.write()
        with self.assertRaises(ValueError):read_version("release")
        self.value["elements"]=self.value["elements"][:1];self.value["applicationId"]="foreign";self.write()
        with self.assertRaises(ValueError):read_version("release")
    def test_invalid_versions_never_fall_back_to_one(self):
        for value in (True,0,-1,"155",2_147_483_647):
            self.value["elements"][0]["versionCode"]=value;self.write()
            with self.subTest(value=value),self.assertRaises(ValueError):read_version("release")
    def test_caller_cannot_choose_a_path(self):
        with self.assertRaises(ValueError):read_version("../../foreign")

if __name__=="__main__":unittest.main()

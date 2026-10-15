#!/usr/bin/env python3
import json
import os
import tempfile
import unittest
from pathlib import Path
from android_artifact_version import read_version

class ActualVersionTest(unittest.TestCase):
    def setUp(self):
        self.original=Path.cwd();tmp=tempfile.TemporaryDirectory();self.addCleanup(tmp.cleanup)
        os.chdir(tmp.name);self.addCleanup(os.chdir,self.original)
        self.path=Path("android/app/build/outputs/apk/release/output-metadata.json");self.path.parent.mkdir(parents=True)
        self.value={"applicationId":"com.atenea.android","elements":[{"versionCode":1000012,"versionName":"0.6.12"}]}
    def write(self):self.path.write_text(json.dumps(self.value))
    def test_actual_version_not_development_default(self):
        self.write();self.assertEqual((1000012,"0.6.12"),read_version("release"))
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

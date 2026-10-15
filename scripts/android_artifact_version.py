#!/usr/bin/env python3
"""Version of the actual single APK output, never guessed from Gradle source."""
import json
import re
import sys
from pathlib import Path

def read_version(variant):
    if variant not in {"debug","release"}:
        raise ValueError("Unsupported fixed Android variant")
    metadata=json.loads(Path(f"android/app/build/outputs/apk/{variant}/output-metadata.json").read_text())
    elements=metadata.get("elements")
    if metadata.get("applicationId")!="com.atenea.android" or not isinstance(elements,list) or len(elements)!=1:
        raise ValueError("Ambiguous Android artifact identity")
    code,name=elements[0].get("versionCode"),elements[0].get("versionName")
    if type(code) is not int or not 0<code<2_100_000_000 or not isinstance(name,str) or not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+",name):
        raise ValueError("Invalid actual Android version")
    return code,name

if __name__=="__main__":
    if len(sys.argv)!=2:raise SystemExit(2)
    print(*read_version(sys.argv[1]))

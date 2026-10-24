#!/usr/bin/env python3
"""Validate a release tag against the Android version before signing anything."""
import re
import sys
from pathlib import Path


def metadata(tag: str, build_file: Path) -> dict[str, str]:
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", tag):
        raise ValueError("Release tag must use vMAJOR.MINOR.PATCH")
    source = build_file.read_text()
    version = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.MULTILINE)
    code = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', source, re.MULTILINE)
    if len(version) != 1 or len(code) != 1:
        raise ValueError("Expected exactly one literal Android versionName and versionCode")
    if tag != f"v{version[0]}":
        raise ValueError(f"Tag {tag} does not match Android versionName {version[0]}")
    return {"version": version[0], "version_code": code[0]}


if __name__ == "__main__":
    try:
        result = metadata(sys.argv[1], Path(__file__).resolve().parents[1] / "app/build.gradle.kts")
    except (ValueError, IndexError) as error:
        sys.exit(str(error))
    for key, value in result.items():
        print(f"{key}={value}")

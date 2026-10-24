#!/usr/bin/env python3
"""Create user-facing release notes from the matching changelog section."""
import re
import sys
from pathlib import Path


def notes_for_version(changelog: str, version: str) -> str:
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Expected MAJOR.MINOR.PATCH')
    match = re.search(r'^## ' + re.escape(version) + r'(?:\s[^\n]*)?\n(.*?)(?=^## |\Z)', changelog, re.M | re.S)
    if not match or not match.group(1).strip():
        raise ValueError(f'Add CHANGELOG.md notes for {version} before releasing')
    return match.group(1).strip()


if __name__ == '__main__':
    version = sys.argv[1]
    try:
        changes = notes_for_version(Path('CHANGELOG.md').read_text(), version)
    except ValueError as error:
        sys.exit(str(error))
    print(f'## What\'s new in Raag Music {version}\n\n{changes}\n')
    print(f'Install Raag-Music-{version}.apk on Android 8.0 or newer. Verify downloads using SHA256SUMS.txt.')
    print('Source and dependency inventories are attached.\n')
    print('<!-- Maintainer: test the signed APK before publishing. Attach exact corresponding sources for copyleft dependencies or a compliant source-access mechanism; see docs/RELEASING.md. Remove this comment once reviewed. -->')

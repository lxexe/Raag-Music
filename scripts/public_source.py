#!/usr/bin/env python3
"""Audit an allowlisted source snapshot without reading or exporting Git history."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SKIP_DIRS = {".git", ".gradle", ".gradle-user", ".kotlin", ".idea", ".vscode", ".codex", ".agents", ".aws", "build", "__pycache__", ".pytest_cache"}
PRIVATE_NAMES = {"local.properties", "google-services.json", "keystore.properties", "signing.properties"}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".pem", ".key", ".p12", ".pfx", ".apk", ".aab", ".apks", ".zip", ".hprof", ".log", ".trace"}
PATTERNS = {
    "private key": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
    "GitHub token": re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b"),
    "AWS access ID": re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "Slack token": re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{15,}"),
    "OpenAI token": re.compile(r"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}"),
    "Google API key": re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"),
    "personal contact": re.compile(r"[\w.+-]+@(?:gmail|outlook|hotmail|yahoo)\.com", re.I),
    "local home path": re.compile(r"/(?:Users|home)/[A-Za-z0-9_.-]+/"),
}

LEGAL_PAIRS = [
    ("LICENSE", "GPL-3.0.txt"),
    ("PRIVACY.md", "PRIVACY.txt"),
    ("THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.txt"),
    ("LICENSES/Apache-2.0.txt", "Apache-2.0.txt"),
    ("LICENSES/MPL-2.0.txt", "MPL-2.0.txt"),
    ("LICENSES/jsoup-MIT.txt", "jsoup-MIT.txt"),
    ("LICENSES/protobuf-BSD.txt", "protobuf-BSD.txt"),
    ("LICENSES/CC-BY-2.5.txt", "CC-BY-2.5.txt"),
    ("LICENSES/jsr305-NOTICE.txt", "jsr305-NOTICE.txt"),
    ("LICENSES/nanojson-NOTICE.txt", "nanojson-NOTICE.txt"),
    ("LICENSES/desugar-GPL-2.0-with-classpath-exception.txt", "desugar-GPL-2.0-with-classpath-exception.txt"),
    ("LICENSES/desugar-ASSEMBLY_EXCEPTION.txt", "desugar-ASSEMBLY_EXCEPTION.txt"),
    ("LICENSES/desugar-ADDITIONAL_LICENSE_INFO.txt", "desugar-ADDITIONAL_LICENSE_INFO.txt"),
]


def reject_symlinks(root: Path, path: Path) -> None:
    current = root
    for part in path.relative_to(root).parts:
        current = current / part
        if current.is_symlink():
            raise ValueError(f"Symlink not allowed: {current.relative_to(root)}")


def public_files(root: Path) -> list[Path]:
    manifest = json.loads((root / "public-source.json").read_text())
    result: set[Path] = set()

    def add(path: Path) -> None:
        relative = path.relative_to(root)
        if any(part in SKIP_DIRS for part in relative.parts[:-1]):
            raise ValueError(f"Private/generated directory in public source: {relative}")
        reject_symlinks(root, path)
        if not path.is_file():
            raise ValueError(f"Missing public file: {relative}")
        name = path.name.lower()
        if name in PRIVATE_NAMES or (name.startswith(".env") and name != ".env.example") or "firebase-adminsdk" in name or path.suffix.lower() in PRIVATE_SUFFIXES:
            raise ValueError(f"Private/generated file in public source: {relative}")
        if path.suffix.lower() == ".jar" and relative.as_posix() != "gradle/wrapper/gradle-wrapper.jar":
            raise ValueError(f"Unexpected binary dependency: {relative}")
        if path.stat().st_size > 2_000_000:
            raise ValueError(f"Unexpected large source file: {relative}")
        result.add(relative)

    for name in manifest["files"]:
        path = root / name
        if Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError("Source manifest must contain relative in-tree paths")
        add(path)
    for name in manifest["directories"]:
        if Path(name).is_absolute() or ".." in Path(name).parts:
            raise ValueError("Source manifest must contain relative in-tree paths")
        directory = root / name
        reject_symlinks(root, directory)
        if directory.is_symlink() or not directory.is_dir():
            raise ValueError(f"Invalid source directory: {name}")
        for base, dirs, files in os.walk(directory, followlinks=False):
            for child in dirs:
                if (Path(base) / child).is_symlink():
                    raise ValueError(f"Symlink not allowed: {(Path(base) / child).relative_to(root)}")
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for child in files:
                if child == ".DS_Store" or child.endswith(".pyc"):
                    continue
                add(Path(base) / child)
    return sorted(result)


def audit(root: Path, files: list[Path]) -> list[str]:
    findings = []
    for relative in files:
        content = (root / relative).read_bytes()
        if b"\0" in content:
            continue
        text = content.decode("utf-8", errors="replace")
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                # Never echo the matched credential or private value.
                findings.append(f"{relative}:{line}: possible {label}")
        if "soundhelix.com" in text.lower() and relative.parts[:3] == ("app", "src", "main"):
            findings.append(f"{relative}: demo audio reference in production source")
    return findings


def verify_notices(root: Path) -> list[str]:
    findings = []
    for source, asset in LEGAL_PAIRS:
        bundled = f"app/src/main/assets/licenses/{asset}"
        if not (root / bundled).is_file() or (root / source).read_bytes() != (root / bundled).read_bytes():
            findings.append(f"Bundled legal document needs sync: {bundled}")
    if not (root / "app/src/main/assets/fonts/PlusJakartaSans-OFL.txt").is_file():
        findings.append("Missing bundled font license")
    # Curated list must cover every document the offline reader offers.
    reader = (root / "app/src/main/java/com/raagmusic/app/ui/LegalInformation.kt").read_text()
    for asset in re.findall(r'LegalDocument\("[^"]+", "([^"]+)"\)', reader):
        if not (root / "app/src/main/assets" / asset).is_file():
            findings.append(f"Missing offline legal document: {asset}")
    return findings


def checked_files(root: Path = ROOT) -> list[Path]:
    files = public_files(root)
    findings = audit(root, files) + verify_notices(root)
    if findings:
        raise ValueError("Public source audit failed:\n" + "\n".join(findings))
    return files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--list", action="store_true", help="print audited public paths")
    args = parser.parse_args()
    try:
        files = checked_files()
    except (ValueError, OSError) as error:
        print(error, file=sys.stderr)
        return 1
    if args.list:
        print("\n".join(path.as_posix() for path in files))
    print(f"Public source audit passed: {len(files)} files. Git history is not audited or exported.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Create an audited source ZIP with no Git history, credentials or build outputs."""
import argparse
import hashlib
from pathlib import Path
import stat
import sys
import zipfile

from public_source import ROOT, checked_files


def export(root: Path, files: list[Path], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for relative in files:
            source = root / relative
            info = zipfile.ZipInfo("raag-music/" + relative.as_posix(), date_time=(2026, 1, 1, 0, 0, 0))
            info.create_system = 3
            executable = relative.as_posix() == "gradlew" or bool(source.stat().st_mode & stat.S_IXUSR)
            info.external_attr = (stat.S_IFREG | (0o755 if executable else 0o644)) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, source.read_bytes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "dist/raag-music-source.zip")
    args = parser.parse_args()
    try:
        files = checked_files()
        output = args.output.resolve()
        if output == ROOT or output in [ROOT / file for file in files]:
            raise ValueError("Export must not overwrite a source file")
        export(ROOT, files, output)
        checksum = hashlib.sha256(output.read_bytes()).hexdigest()
        output.with_suffix(output.suffix + ".sha256").write_text(f"{checksum}  {output.name}\n")
        print(f"Exported {len(files)} audited files to {output.name} ({output.stat().st_size:,} bytes).")
        print("No .git directory, history, local configuration, signing keys or build outputs included.")
        return 0
    except (ValueError, OSError) as error:
        print(error, file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

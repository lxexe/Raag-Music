#!/usr/bin/env python3
"""Copy reviewed legal documents into the app's offline About reader."""
import shutil

from public_source import LEGAL_PAIRS, ROOT


def main() -> None:
    destination = ROOT / "app/src/main/assets/licenses"
    destination.mkdir(parents=True, exist_ok=True)
    for source, asset in LEGAL_PAIRS:
        shutil.copyfile(ROOT / source, destination / asset)
    print(f"Synced {len(LEGAL_PAIRS)} legal documents.")


if __name__ == "__main__":
    main()

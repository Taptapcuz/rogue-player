#!/usr/bin/env python3
"""Snapshot the MYTV sources + the current debug APK before a risky change.

    python scripts/backup.py            # -> ../backups/mytv-src-<stamp>.zip + mytv-debug-<stamp>.apk

Sources only: build/, .gradle/, .idea/, .cxx/ and .git/ are skipped, which keeps the
zip around 100 KB instead of hundreds of MB. A raw folder copy is not an option on this
tree: it contains an unopenable `\\.\nul` device entry that makes os.path.relpath throw.
"""
import datetime
import os
import shutil
import sys
import zipfile

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(os.path.dirname(PROJECT), "backups")
SKIP_DIRS = {"build", ".gradle", ".idea", ".cxx", ".git"}


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    zip_path = os.path.join(OUT, f"mytv-src-{stamp}.zip")

    written = 0
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as z:
        for root, dirs, files in os.walk(PROJECT):
            dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
            for name in files:
                path = os.path.join(root, name)
                try:
                    rel = os.path.relpath(path, PROJECT)
                except ValueError:       # the \\.\nul device entry
                    continue
                if not os.path.isfile(path):
                    continue
                try:
                    z.write(path, rel)
                    written += 1
                except OSError:
                    continue

    print(f"source snapshot: {zip_path} ({written} files, "
          f"{os.path.getsize(zip_path) / 1024:.0f} KB)")

    apk = os.path.join(PROJECT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
    if os.path.exists(apk):
        dest = os.path.join(OUT, f"mytv-debug-{stamp}.apk")
        shutil.copy2(apk, dest)
        print(f"apk:             {dest} ({os.path.getsize(dest) / 1024 / 1024:.1f} MB)")
    else:
        print("apk:             not built yet, skipped")
    return 0


if __name__ == "__main__":
    sys.exit(main())

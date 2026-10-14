#!/usr/bin/env python3
"""Verify and push the curated photo evaluation set (stdlib only)."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess


ROOT = Path(__file__).resolve().parents[1] / "testsets/specialized_v1"
DEST = "/sdcard/Pictures/DiffuseTestset_v1"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--feature", help="Only this feature; see --list")
    parser.add_argument("--serial", help="adb device serial")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--verify", action="store_true", help="Local integrity check only")
    parser.add_argument("--dry-run", action="store_true", help="Print commands, no adb calls")
    args = parser.parse_args()
    assets = json.loads((ROOT / "assets.json").read_text())
    cases = json.loads((ROOT / "cases.json").read_text())
    features = sorted({c["feature"] for c in cases})
    if args.list:
        for name in features:
            print(name, sum(name in a["features"] for a in assets), "images")
        return
    if args.feature and args.feature not in features:
        parser.error("Unknown feature; use --list")
    selected = [a for a in assets if not args.feature or args.feature in a["features"]]
    ids = {a["id"] for a in assets}
    assert len(ids) == len(assets), "Duplicate asset ID"
    assert len({c["id"] for c in cases}) == len(cases), "Duplicate case ID"
    for case in cases:
        assert set(case["assets"]) <= ids, case["id"]
    for asset in selected:
        for key, hash_key in [("file", "sha256"), ("device_file", "device_sha256")]:
            path = ROOT / asset[key]
            assert path.resolve().is_relative_to(ROOT.resolve()), path
            assert hashlib.sha256(path.read_bytes()).hexdigest() == asset[hash_key], path
    print(f"Verified {len(selected)} images; {len(cases)} case references valid.")
    if args.verify:
        return
    adb = ["adb"] + (["-s", args.serial] if args.serial else [])
    if not args.dry_run:
        result = subprocess.run([*adb, "devices"], check=True, text=True, capture_output=True)
        devices = [line.split() for line in result.stdout.splitlines()[1:] if line.strip()]
        if args.serial:
            ready = any(d[0] == args.serial and d[1] == "device" for d in devices)
        else:
            ready = len(devices) == 1 and devices[0][1] == "device"
        if not ready:
            parser.error("Choose exactly one online, authorized device with --serial")

    def run(*command):
        full = [*adb, *command]
        print(shlex.join(full))
        if not args.dry_run:
            subprocess.run(full, check=True)

    run("shell", "mkdir", "-p", DEST)
    for asset in selected:
        path = ROOT / asset["device_file"]
        target = f"{DEST}/{path.name}"
        run("push", str(path), target)
        # Ask MediaStore to index each file so Photo Picker can discover it.
        run("shell", "am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d", f"file://{target}")
    print("Dry run complete." if args.dry_run else
          "Push complete; verify the album is visible in the device Photo Picker.")


if __name__ == "__main__":
    main()

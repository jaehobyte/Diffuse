"""Smoke client for POST /v1/multishot: sends images in the given order, checks the response
contract on the client side, and saves the composite PNG and its metadata.

    MULTISHOT_AUTH_TOKEN=... .venv/bin/python scripts/smoke_client.py \
        --base-url http://127.0.0.1:8086 --out smoke-out/three a.jpg b.jpg c.jpg

The token is read from the environment and never printed. Exit 0 only for a 200 whose
contract checks pass (or, with --expect-error CODE, for that error).
"""

from __future__ import annotations

import argparse
import io
import json
import os
import sys
import time
from pathlib import Path

import httpx
from PIL import Image
from python_multipart.multipart import parse_options_header


def parse_response(response: httpx.Response) -> tuple[dict, bytes]:
    media, options = parse_options_header(response.headers.get("content-type", ""))
    if media != b"multipart/form-data" or b"boundary" not in options:
        raise SystemExit(f"not multipart: {response.headers.get('content-type')}")
    boundary = b"--" + options[b"boundary"]
    parts: dict[str, tuple[str, bytes]] = {}
    for section in response.content.split(boundary)[1:-1]:
        head, _, body = section[2:-2].partition(b"\r\n\r\n")
        headers = dict(
            (k.strip().lower(), v.strip()) for k, _, v in (line.decode().partition(":") for line in head.split(b"\r\n"))
        )
        name = parse_options_header(headers["content-disposition"])[1][b"name"].decode()
        parts[name] = (headers.get("content-type", ""), body)
    if list(parts) != ["metadata", "image"]:
        raise SystemExit(f"unexpected parts: {list(parts)}")
    if parts["metadata"][0] != "application/json" or parts["image"][0] != "image/png":
        raise SystemExit("unexpected part content types")
    return json.loads(parts["metadata"][1]), parts["image"][1]


def check(metadata: dict, png: bytes, count: int) -> list[str]:
    problems = []
    if metadata.get("contract_version") != 1:
        problems.append("contract_version")
    if metadata.get("input_count") != count or metadata.get("hero_index") != count - 1:
        problems.append("input_count/hero_index")
    shots = metadata.get("shots", [])
    if [s.get("input_index") for s in shots] != list(range(count)):
        problems.append("shots not in input order")
    if [s.get("is_hero") for s in shots] != [False] * (count - 1) + [True]:
        problems.append("hero flag")
    hero_y = shots[-1]["anchor"][1] if shots else None
    if any(abs(s["anchor"][1] - hero_y) > 1e-5 for s in shots):
        problems.append("anchors not on the hero's height")
    with Image.open(io.BytesIO(png), formats=["PNG"]) as img:
        img.load()
        if img.mode != "RGBA" or img.size != (metadata.get("width"), metadata.get("height")):
            problems.append(f"png {img.mode} {img.size}")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://127.0.0.1:8086")
    parser.add_argument("--out", type=Path, required=True, help="directory for result.png and metadata.json")
    parser.add_argument("--metadata", help='JSON for the metadata part, e.g. \'{"spacing": 0.8}\'')
    parser.add_argument("--expect-error", help="succeed only if the server answers this error code")
    parser.add_argument("--timeout", type=float, default=330.0)
    parser.add_argument("images", nargs="+", type=Path, help="in time order; the last is background and hero")
    args = parser.parse_args()

    token = os.environ.get("MULTISHOT_AUTH_TOKEN", "")
    if not token:
        print("set MULTISHOT_AUTH_TOKEN", file=sys.stderr)
        return 2
    # Repeated field name, in the order given: that order is the time order.
    files = [("images", (path.name, path.read_bytes(), "application/octet-stream")) for path in args.images]
    data = {"metadata": args.metadata} if args.metadata is not None else None
    started = time.monotonic()
    response = httpx.post(
        f"{args.base_url.rstrip('/')}/v1/multishot",
        headers={"Authorization": f"Bearer {token}"},
        files=files,
        data=data,
        timeout=args.timeout,
    )
    elapsed = time.monotonic() - started

    if response.status_code != 200:
        body = response.json() if response.headers.get("content-type", "").startswith("application/json") else {}
        print(f"HTTP {response.status_code} {json.dumps(body)} ({elapsed:.1f}s)")
        return 0 if args.expect_error and body.get("error") == args.expect_error else 1
    if args.expect_error:
        print(f"expected {args.expect_error}, got 200")
        return 1
    metadata, png = parse_response(response)
    problems = check(metadata, png, len(args.images))
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "result.png").write_bytes(png)
    (args.out / "metadata.json").write_text(json.dumps(metadata, indent=2))
    print(f"HTTP 200 {metadata['width']}x{metadata['height']} in {elapsed:.1f}s → {args.out / 'result.png'}")
    for shot in metadata["shots"]:
        print(
            f"  input {shot['input_index']} slot {shot['slot_index']} anchor ({shot['anchor'][0]:.4f}, "
            f"{shot['anchor'][1]:.4f}) opacity {shot['opacity']:.4f}{' hero' if shot['is_hero'] else ''}"
        )
    print(f"  warnings {metadata['warnings']}")
    if problems:
        print("CONTRACT PROBLEMS: " + ", ".join(problems))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

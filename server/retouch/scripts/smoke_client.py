#!/usr/bin/env python3
"""Real-model smoke over HTTP, validating wire contract v1 client-side.

    RETOUCH_AUTH_TOKEN=... smoke_client.py --base-url http://127.0.0.1:8084 \
        --manifest /tmp/case/manifest.json --all-kinds [--negative-auth] [--repeat 5] [--out DIR]

The token comes from RETOUCH_AUTH_TOKEN or --token-file (which must not be group/world readable).
Neither the token nor any image/mask payload is ever printed. Exit status is non-zero on any
contract violation, unexpected status, or unmet expected outcome.

Manifest (paths relative to the manifest):

    {"cases": [{"id": "c1", "image": "c1_roi.png",
                "allowed_mask": "c1_allowed.png",               # shared by every kind, and/or
                "allowed_masks": {"shine": "c1_shine.png"},     # per kind (wins over shared)
                "expected": {"blemish": "no_change"}}]}         # optional: corrected|no_change|any
"""

from __future__ import annotations

import argparse
import json
import os
import re
import secrets
import stat
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

import cv2
import numpy as np

KINDS = ("blemish", "shine", "dark_circles", "shaving_shadow")
HEALTH_TIMEOUT_S = 5
INFERENCE_TIMEOUT_S = 60


class Violation(Exception):
    pass


def read_token(args) -> str:
    if args.token_file:
        path = Path(args.token_file)
        mode = path.stat().st_mode
        if mode & (stat.S_IRWXG | stat.S_IRWXO):
            raise SystemExit(f"{path} is accessible by group/others; chmod 600 it first")
        token = path.read_text().strip()
    else:
        token = os.environ.get("RETOUCH_AUTH_TOKEN", "").strip()
    if not token:
        raise SystemExit("no token: set RETOUCH_AUTH_TOKEN or pass --token-file")
    return token


def http(method: str, url: str, headers: dict, body: bytes | None, timeout: float):
    request = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, dict(response.headers.items()), response.read()
    except urllib.error.HTTPError as exc:
        return exc.code, dict(exc.headers.items()), exc.read()


def header(headers: dict, name: str) -> str | None:
    for key, value in headers.items():
        if key.lower() == name.lower():
            return value
    return None


def encode_multipart(parts):
    boundary = "smoke-" + secrets.token_hex(12)
    chunks = []
    for name, content_type, body in parts:
        chunks.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\nContent-Type: {content_type}\r\n\r\n'.encode()
        )
        chunks.append(body + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def parse_multipart(content_type: str, body: bytes) -> dict[str, tuple[str, bytes]]:
    match = re.search(r'boundary="?([^";]+)"?', content_type or "")
    if not content_type or not content_type.startswith("multipart/form-data") or not match:
        raise Violation("response is not multipart/form-data")
    delimiter = b"--" + match.group(1).encode()
    sections = body.split(delimiter)
    if len(sections) < 3 or not sections[-1].startswith(b"--"):
        raise Violation("multipart response is not terminated")
    parts: dict[str, tuple[str, bytes]] = {}
    for section in sections[1:-1]:
        if not section.startswith(b"\r\n") or not section.endswith(b"\r\n"):
            raise Violation("malformed multipart section")
        head, sep, content = section[2:-2].partition(b"\r\n\r\n")
        if not sep:
            raise Violation("multipart section without headers")
        fields = {}
        for line in head.decode("latin-1").split("\r\n"):
            key, _, value = line.partition(":")
            fields[key.strip().lower()] = value.strip()
        name_match = re.search(r'name="([^"]*)"', fields.get("content-disposition", ""))
        if not name_match:
            raise Violation("part without a name")
        name = name_match.group(1)
        if name in parts:
            raise Violation(f"duplicate part {name}")
        parts[name] = (fields.get("content-type", ""), content)
    return parts


def png_header(data: bytes) -> tuple[int, int, int, int]:
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise Violation("part is not a PNG")
    width = int.from_bytes(data[16:20], "big")
    height = int.from_bytes(data[20:24], "big")
    return width, height, data[24], data[25]


def load_rgba(path: Path) -> tuple[np.ndarray, bytes]:
    data = path.read_bytes()
    array = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if array is None:
        raise SystemExit(f"cannot decode {path}")
    if array.ndim == 2:
        array = cv2.cvtColor(array, cv2.COLOR_GRAY2RGBA)
    elif array.shape[2] == 3:
        array = cv2.cvtColor(array, cv2.COLOR_BGR2RGBA)
    else:
        array = cv2.cvtColor(array, cv2.COLOR_BGRA2RGBA)
    if png_header(data)[2:] != (8, 6):
        ok, buf = cv2.imencode(".png", cv2.cvtColor(array, cv2.COLOR_RGBA2BGRA))
        data = buf.tobytes()
    return array, data


def load_mask(path: Path, shape) -> tuple[np.ndarray, bytes]:
    data = path.read_bytes()
    mask = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_UNCHANGED)
    if mask is None or mask.ndim != 2 or mask.shape != shape:
        raise SystemExit(f"{path} must be a single-channel mask of the ROI size")
    if np.any((mask != 0) & (mask != 255)):
        raise SystemExit(f"{path} is not binary")
    if png_header(data)[2:] != (8, 0):
        ok, buf = cv2.imencode(".png", mask)
        data = buf.tobytes()
    return mask, data


def validate(kind, request_id, engine_version, image, mask, status, headers, body):
    if status != 200:
        raise Violation(f"HTTP {status} {body[:200]!r}")
    parts = parse_multipart(header(headers, "content-type"), body)
    if "metadata" not in parts:
        raise Violation("no metadata part")
    meta = json.loads(parts["metadata"][1])
    height, width = image.shape[:2]
    expected = {
        "request_id": request_id,
        "contract_version": 1,
        "kind": kind,
        "engine_version": engine_version,
        "width": width,
        "height": height,
    }
    for key, value in expected.items():
        if meta.get(key) != value:
            raise Violation(f"metadata {key} mismatch")
    outcome = meta.get("outcome")
    if not isinstance(meta.get("timing_ms"), dict):
        raise Violation("timing_ms missing")
    result = {"outcome": outcome, "timing_ms": meta["timing_ms"], "changed_px": 0, "support_px": 0}
    if outcome == "no_change":
        if set(parts) != {"metadata"}:
            raise Violation("no_change must carry only metadata")
        return result, None, None
    if outcome != "corrected":
        raise Violation(f"unknown outcome {outcome!r}")
    if set(parts) != {"metadata", "candidate", "change_support"}:
        raise Violation(f"corrected parts are {sorted(parts)}")
    cand_data, support_data = parts["candidate"][1], parts["change_support"][1]
    if png_header(cand_data) != (width, height, 8, 6):
        raise Violation("candidate is not 8-bit RGBA at ROI size")
    if png_header(support_data) != (width, height, 8, 0):
        raise Violation("change_support is not 8-bit gray at ROI size")
    candidate = cv2.cvtColor(cv2.imdecode(np.frombuffer(cand_data, np.uint8), cv2.IMREAD_UNCHANGED), cv2.COLOR_BGRA2RGBA)
    support_raw = cv2.imdecode(np.frombuffer(support_data, np.uint8), cv2.IMREAD_UNCHANGED)
    if np.any((support_raw != 0) & (support_raw != 255)):
        raise Violation("change_support is not binary")
    support = support_raw == 255
    if not support.any():
        raise Violation("corrected with an empty support")
    if np.any(support & ((mask == 0) | (image[:, :, 3] == 0))):
        raise Violation("support is outside allowed ∩ alpha>0")
    if not np.array_equal(candidate[~support], image[~support]):
        raise Violation("candidate differs from input outside support")
    if not np.array_equal(candidate[:, :, 3], image[:, :, 3]):
        raise Violation("candidate alpha differs from input")
    result["changed_px"] = int(np.any(candidate != image, axis=2).sum())
    result["support_px"] = int(support.sum())
    return result, cand_data, support_data


def negative_auth(base_url: str, token: str) -> list[str]:
    failures = []
    status, headers, _ = http("GET", f"{base_url}/health", {}, None, HEALTH_TIMEOUT_S)
    if status != 401 or (header(headers, "www-authenticate") or "").lower() != "bearer":
        failures.append(f"health without token: expected 401 + WWW-Authenticate, got {status}")
    status, _, _ = http("GET", f"{base_url}/health", {"Authorization": "Bearer " + "x" * 16}, None, HEALTH_TIMEOUT_S)
    if status != 403:
        failures.append(f"health with wrong token: expected 403, got {status}")
    status, _, _ = http("POST", f"{base_url}/v1/retouch", {"Content-Type": "text/plain"}, b"x", HEALTH_TIMEOUT_S)
    if status != 401:
        failures.append(f"retouch without token: expected 401, got {status}")
    print(f"negative-auth: {'ok' if not failures else 'FAILED'}")
    return failures


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--manifest", type=Path)
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--all-kinds", action="store_true")
    group.add_argument("--kind", choices=KINDS, action="append")
    parser.add_argument("--token-file")
    parser.add_argument("--negative-auth", action="store_true")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    token = read_token(args)
    auth = {"Authorization": f"Bearer {token}"}
    failures: list[str] = []

    if args.negative_auth:
        failures += negative_auth(base_url, token)

    started = time.perf_counter()
    status, _, body = http("GET", f"{base_url}/health", auth, None, HEALTH_TIMEOUT_S)
    health_ms = (time.perf_counter() - started) * 1000
    if status != 200:
        print(f"health: HTTP {status} ({health_ms:.0f} ms)")
        return 1
    health = json.loads(body)
    evaluation = health.get("evaluation_engines", {})
    print(
        f"health: {health['status']} kinds={health['supported_kinds']} engines={health['engines']} "
        f"evaluation_engines={evaluation} ({health_ms:.0f} ms)"
    )
    engines = {**evaluation, **health["engines"]}
    if health.get("contract_version") != 1 or health.get("status") != "ready":
        return 1

    if args.manifest is None:
        return 1 if failures else 0
    kinds = list(KINDS) if args.all_kinds else (args.kind or [])
    if not kinds:
        raise SystemExit("choose --all-kinds or --kind")
    manifest = json.loads(args.manifest.read_text())
    base_dir = args.manifest.parent
    latencies: dict[str, list[float]] = {}

    for case in manifest["cases"]:
        image, image_png = load_rgba(base_dir / case["image"])
        for kind in kinds:
            if kind not in engines:
                failures.append(f"{case['id']}/{kind}: not loaded by the server")
                print(f"{case['id']} kind={kind} SKIPPED: not in supported_kinds or evaluation_engines")
                continue
            if kind not in health["supported_kinds"]:
                print(f"{case['id']} kind={kind}: evaluation engine, not offered to the app")
            mask_name = case.get("allowed_masks", {}).get(kind, case.get("allowed_mask"))
            if mask_name is None:
                print(f"{case['id']} kind={kind} skipped: no allowed mask for this kind")
                continue
            mask, mask_png = load_mask(base_dir / mask_name, image.shape[:2])
            for n in range(args.repeat):
                safe_case = re.sub(r"[^A-Za-z0-9_-]", "-", str(case["id"]))[:20]
                request_id = f"smoke-{kind}-{safe_case}-{n}-{secrets.token_hex(4)}"[:64]
                meta = {
                    "request_id": request_id,
                    "contract_version": 1,
                    "kind": kind,
                    "expected_engine_version": engines[kind],
                    "width": int(image.shape[1]),
                    "height": int(image.shape[0]),
                }
                body, content_type = encode_multipart(
                    [
                        ("metadata", "application/json", json.dumps(meta).encode()),
                        ("image", "image/png", image_png),
                        ("allowed_mask", "image/png", mask_png),
                    ]
                )
                started = time.perf_counter()
                status, headers, response = http(
                    "POST", f"{base_url}/v1/retouch", {**auth, "Content-Type": content_type}, body, INFERENCE_TIMEOUT_S
                )
                round_trip = (time.perf_counter() - started) * 1000
                try:
                    result, cand, support = validate(
                        kind, request_id, engines[kind], image, mask, status, headers, response
                    )
                except Violation as exc:
                    failures.append(f"{request_id}: {exc}")
                    print(f"request_id={request_id} kind={kind} status={status} VIOLATION: {exc}")
                    continue
                latencies.setdefault(kind, []).append(round_trip)
                expected = case.get("expected", {}).get(kind, "any")
                if expected != "any" and result["outcome"] != expected:
                    failures.append(f"{request_id}: expected {expected}, got {result['outcome']}")
                print(
                    f"request_id={request_id} kind={kind} status={status} outcome={result['outcome']} "
                    f"round_trip_ms={round_trip:.0f} server_ms={json.dumps(result['timing_ms'], separators=(',', ':'))} "
                    f"support_px={result['support_px']} changed_px={result['changed_px']}"
                )
                if args.out and n == 0:
                    args.out.mkdir(parents=True, exist_ok=True)
                    if cand is not None:
                        (args.out / f"{case['id']}_{kind}_candidate.png").write_bytes(cand)
                        (args.out / f"{case['id']}_{kind}_support.png").write_bytes(support)

    for kind, values in latencies.items():
        ordered = sorted(values)
        p50 = ordered[len(ordered) // 2]
        p95 = ordered[min(len(ordered) - 1, int(round(0.95 * (len(ordered) - 1))))]
        print(f"latency kind={kind} n={len(ordered)} p50_ms={p50:.0f} p95_ms={p95:.0f}")
    if failures:
        print(f"FAILED: {len(failures)} problem(s)")
        for failure in failures:
            print(f"  {failure}")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())

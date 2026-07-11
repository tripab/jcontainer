#!/usr/bin/env python3
"""Run a dependency-free HTTP load pattern and write request traces."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import time
import urllib.error
import urllib.request
from pathlib import Path


def effective_rps(pattern: str, base_rps: int, fraction: float) -> int:
    if pattern == "steady":
        return base_rps
    if pattern == "spike":
        return base_rps * 5 if 0.4 < fraction < 0.6 else base_rps
    if pattern == "oscillating":
        return max(0, int(base_rps * (1.0 + math.sin(fraction * 4 * math.pi))))
    raise ValueError(f"unsupported pattern: {pattern}")


def percentile(values: list[float], pct: int) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    idx = math.ceil(pct / 100.0 * len(ordered)) - 1
    return ordered[max(0, idx)]


def send_one(url: str, timeout_seconds: float, started_at: float) -> dict:
    request_started = time.time()
    record = {
        "relative_start_seconds": request_started - started_at,
        "status": None,
        "latency_ms": None,
        "outcome": "error",
    }
    try:
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout_seconds) as response:
            response.read()
            latency_ms = (time.time() - request_started) * 1000.0
            record["status"] = response.status
            record["latency_ms"] = latency_ms
            record["outcome"] = "success" if response.status == 200 else "error"
    except TimeoutError:
        record["outcome"] = "timeout"
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, TimeoutError):
            record["outcome"] = "timeout"
        else:
            record["error"] = str(exc.reason)
    except Exception as exc:  # noqa: BLE001 - traces should capture unexpected client failures.
        record["error"] = str(exc)
    return record


def write_json(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True)
    parser.add_argument("--pattern", required=True, choices=("steady", "spike", "oscillating"))
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--base-rps", type=int, required=True)
    parser.add_argument("--timeout-ms", type=int, default=250)
    parser.add_argument("--trace-jsonl", type=Path, required=True)
    parser.add_argument("--summary-json", type=Path, required=True)
    args = parser.parse_args()

    if args.duration_seconds <= 0:
        raise SystemExit("--duration-seconds must be positive")
    if args.base_rps <= 0:
        raise SystemExit("--base-rps must be positive")
    if args.timeout_ms <= 0:
        raise SystemExit("--timeout-ms must be positive")

    args.trace_jsonl.parent.mkdir(parents=True, exist_ok=True)
    timeout_seconds = args.timeout_ms / 1000.0
    started_at = time.time()
    end_at = started_at + args.duration_seconds
    records: list[dict] = []

    with concurrent.futures.ThreadPoolExecutor(max_workers=max(args.base_rps * 6, 8)) as executor:
        futures: list[concurrent.futures.Future] = []
        tick = 0
        while True:
            now = time.time()
            if now >= end_at:
                break
            fraction = min(1.0, (now - started_at) / args.duration_seconds)
            rps = effective_rps(args.pattern, args.base_rps, fraction)
            for _ in range(rps):
                futures.append(executor.submit(send_one, args.url, timeout_seconds, started_at))
            tick += 1
            sleep_until = started_at + tick
            time.sleep(max(0.0, min(sleep_until, end_at) - time.time()))

        with args.trace_jsonl.open("w", encoding="utf-8") as trace:
            for future in concurrent.futures.as_completed(futures):
                record = future.result()
                records.append(record)
                trace.write(json.dumps(record, sort_keys=True) + "\n")

    successes = [r for r in records if r["outcome"] == "success"]
    timeouts = [r for r in records if r["outcome"] == "timeout"]
    errors = [r for r in records if r["outcome"] == "error"]
    latencies = [float(r["latency_ms"]) for r in successes if r["latency_ms"] is not None]
    total = len(records)
    summary = {
        "url": args.url,
        "pattern": args.pattern,
        "duration_seconds": args.duration_seconds,
        "base_rps": args.base_rps,
        "timeout_ms": args.timeout_ms,
        "total_requests": total,
        "success_count": len(successes),
        "timeout_count": len(timeouts),
        "error_count": len(errors),
        "success_rate": len(successes) / total if total else 0.0,
        "timeout_rate": len(timeouts) / total if total else 0.0,
        "p50_latency_ms": percentile(latencies, 50),
        "p95_latency_ms": percentile(latencies, 95),
    }
    write_json(args.summary_json, summary)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

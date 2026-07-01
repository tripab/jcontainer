#!/usr/bin/env python3
"""Sample cgroup v2 control and telemetry files as JSON lines."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path


KEY_VALUE_FILES = {
    "cpu_stat": "cpu.stat",
    "memory_events": "memory.events",
}

TEXT_FILES = {
    "cpu_max": "cpu.max",
    "memory_current": "memory.current",
    "memory_high": "memory.high",
    "memory_max": "memory.max",
    "memory_pressure": "memory.pressure",
}


def parse_key_value(content: str) -> dict[str, int]:
    values: dict[str, int] = {}
    for line in content.splitlines():
        parts = line.split()
        if len(parts) == 2:
            try:
                values[parts[0]] = int(parts[1])
            except ValueError:
                continue
    return values


def read_text(path: Path) -> str | None:
    try:
        return path.read_text(encoding="utf-8").strip()
    except FileNotFoundError:
        return None


def snapshot(cgroup_path: Path, started_at: float) -> dict:
    record: dict = {
        "observed_at_epoch_seconds": time.time(),
        "relative_seconds": time.time() - started_at,
    }
    for key, filename in TEXT_FILES.items():
        record[key] = read_text(cgroup_path / filename)
    for key, filename in KEY_VALUE_FILES.items():
        content = read_text(cgroup_path / filename)
        record[key] = parse_key_value(content or "")
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cgroup-path", type=Path, required=True)
    parser.add_argument("--duration-seconds", type=int, required=True)
    parser.add_argument("--interval-seconds", type=float, default=1.0)
    parser.add_argument("--output-jsonl", type=Path, required=True)
    args = parser.parse_args()

    if not args.cgroup_path.is_dir():
        raise SystemExit(f"missing cgroup path: {args.cgroup_path}")
    if args.duration_seconds <= 0:
        raise SystemExit("--duration-seconds must be positive")
    if args.interval_seconds <= 0:
        raise SystemExit("--interval-seconds must be positive")

    args.output_jsonl.parent.mkdir(parents=True, exist_ok=True)
    started_at = time.time()
    end_at = started_at + args.duration_seconds
    with args.output_jsonl.open("w", encoding="utf-8") as output:
        while True:
            output.write(json.dumps(snapshot(args.cgroup_path, started_at), sort_keys=True) + "\n")
            output.flush()
            if time.time() >= end_at:
                break
            time.sleep(min(args.interval_seconds, max(0.0, end_at - time.time())))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

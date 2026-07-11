#!/usr/bin/env python3
"""Summarize baseline capture artifacts into JSON and Markdown reports."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    records = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            records.append(json.loads(line))
    return records


def memory_high_delta(samples: list[dict]) -> int:
    values = [sample.get("memory_events", {}).get("high") for sample in samples]
    values = [value for value in values if isinstance(value, int)]
    if len(values) < 2:
        return 0
    return max(0, values[-1] - values[0])


def format_percent(value: float) -> str:
    return f"{value * 100.0:.2f}%"


def format_mib(bytes_value: int | float) -> str:
    return f"{bytes_value / 1024.0 / 1024.0:.1f} MiB"


def scenario_dirs(output_dir: Path) -> list[Path]:
    return sorted(path for path in output_dir.iterdir() if path.is_dir() and (path / "metadata.json").exists())


def build_rows(output_dir: Path) -> list[dict]:
    rows = []
    for scenario_dir in scenario_dirs(output_dir):
        metadata = read_json(scenario_dir / "metadata.json")
        load = read_json(scenario_dir / "load-summary.json")
        telemetry = read_jsonl(scenario_dir / "cgroup-samples.jsonl")
        duration_seconds = int(load["duration_seconds"])
        rows.append({
            "scenario": metadata["scenario"],
            "pattern": metadata["pattern"],
            "p95_latency_ms": load["p95_latency_ms"],
            "timeout_rate": load["timeout_rate"],
            "average_cpu_percent": metadata["cpu_percent"],
            "average_memory_high_bytes": metadata["memory_high_bytes"],
            "pressure_events": memory_high_delta(telemetry),
            "time_in_bundle_seconds": {metadata["scenario"]: duration_seconds},
            "scenario_dir": str(scenario_dir),
        })
    return rows


def markdown(rows: list[dict]) -> str:
    lines = [
        "| Scenario | Pattern | p95 Latency | Timeout Rate | Avg CPU | Avg Memory | Pressure Events | Time In Bundles |",
        "|----------|---------|-------------|--------------|---------|------------|-----------------|-----------------|",
    ]
    for row in rows:
        time_in_bundles = ", ".join(
            f"{bundle}={seconds}s" for bundle, seconds in row["time_in_bundle_seconds"].items()
        )
        lines.append(
            "| {scenario} | {pattern} | {p95:.1f} ms | {timeout} | {cpu:.1f}% | {memory} | {pressure} | {time} |".format(
                scenario=row["scenario"],
                pattern=row["pattern"],
                p95=row["p95_latency_ms"],
                timeout=format_percent(row["timeout_rate"]),
                cpu=row["average_cpu_percent"],
                memory=format_mib(row["average_memory_high_bytes"]),
                pressure=row["pressure_events"],
                time=time_in_bundles,
            )
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    rows = build_rows(args.output_dir)
    if not rows:
        raise SystemExit(f"no scenario artifacts found in {args.output_dir}")

    summary = {"rows": rows}
    (args.output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (args.output_dir / "summary.md").write_text(markdown(rows), encoding="utf-8")
    print(f"Wrote {args.output_dir / 'summary.md'}")
    print(f"Wrote {args.output_dir / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

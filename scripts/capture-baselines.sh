#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_PATH="$PROJECT_DIR/target/jcontainer-1.0-SNAPSHOT.jar"
ROOTFS_DIR="$PROJECT_DIR/rootfs"
OUTPUT_DIR="$PROJECT_DIR/baseline-runs/$(date -u +%Y%m%dT%H%M%SZ)"
DURATION_SECONDS=60
BASE_RPS=10
REQUEST_TIMEOUT_MS=250
PATTERNS="steady,spike,oscillating"
SKIP_BUILD=0
KEEP_CONTAINERS=0
ACTIVE_CONTAINER_ID=""
ACTIVE_RUNNER_PID=""

usage() {
    cat <<EOF
Usage: sudo scripts/capture-baselines.sh [options]

Runs fixed small/medium/large baseline experiments on Linux and writes traces.

Options:
  --output-dir DIR          Output directory (default: baseline-runs/<timestamp>)
  --rootfs DIR              Rootfs directory (default: ./rootfs)
  --duration-seconds N      Load duration per scenario/pattern (default: 60)
  --base-rps N              Base request rate (default: 10)
  --timeout-ms N            HTTP request timeout in ms (default: 250)
  --patterns CSV            steady,spike,oscillating subset (default: all three)
  --skip-build              Use existing target jar
  --keep-containers         Do not remove stopped container registry entries
  -h, --help                Show this help
EOF
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        --rootfs) ROOTFS_DIR="$2"; shift 2 ;;
        --duration-seconds) DURATION_SECONDS="$2"; shift 2 ;;
        --base-rps) BASE_RPS="$2"; shift 2 ;;
        --timeout-ms) REQUEST_TIMEOUT_MS="$2"; shift 2 ;;
        --patterns) PATTERNS="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=1; shift ;;
        --keep-containers) KEEP_CONTAINERS=1; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage; exit 1 ;;
    esac
done

require_command() {
    if ! command -v "$1" >/dev/null 2>&1; then
        echo "Missing required command: $1" >&2
        exit 1
    fi
}

java_cmd() {
    java --enable-native-access=ALL-UNNAMED \
        -cp "$JAR_PATH" \
        org.jcontainer.JContainer "$@"
}

cleanup_container() {
    local container_id="${1:-}"
    local runner_pid="${2:-}"
    if [[ -n "$container_id" ]]; then
        java_cmd stop "$container_id" >/dev/null 2>&1 || true
    fi
    if [[ -n "$runner_pid" ]]; then
        wait "$runner_pid" >/dev/null 2>&1 || true
    fi
    if [[ -n "$container_id" && "$KEEP_CONTAINERS" -eq 0 ]]; then
        java_cmd rm "$container_id" >/dev/null 2>&1 || true
    fi
}

cleanup_active_container() {
    cleanup_container "$ACTIVE_CONTAINER_ID" "$ACTIVE_RUNNER_PID"
}

trap cleanup_active_container EXIT

wait_for_container_id() {
    local stderr_log="$1"
    local runner_pid="$2"
    for _ in $(seq 1 60); do
        if [[ -s "$stderr_log" ]]; then
            local found
            found="$(sed -n 's/^Container \([0-9a-f][0-9a-f]*\) started.*/\1/p' "$stderr_log" | tail -n 1)"
            if [[ -n "$found" ]]; then
                echo "$found"
                return 0
            fi
        fi
        if ! kill -0 "$runner_pid" >/dev/null 2>&1; then
            echo "Container process exited before startup completed. See $stderr_log" >&2
            return 1
        fi
        sleep 0.5
    done
    echo "Timed out waiting for container ID in $stderr_log" >&2
    return 1
}

wait_for_http() {
    local url="$1"
    for _ in $(seq 1 40); do
        if curl -fsS --max-time 1 "$url" >/dev/null 2>&1; then
            return 0
        fi
        sleep 0.5
    done
    echo "Timed out waiting for HTTP service at $url" >&2
    return 1
}

write_metadata() {
    local file="$1"
    local scenario="$2"
    local pattern="$3"
    local cpu="$4"
    local memory_high="$5"
    local memory_max="$6"
    local container_id="$7"
    python3 - "$file" "$scenario" "$pattern" "$cpu" "$memory_high" "$memory_max" "$container_id" <<'PY'
import json
import sys
from pathlib import Path

path, scenario, pattern, cpu, memory_high, memory_max, container_id = sys.argv[1:]
payload = {
    "scenario": scenario,
    "pattern": pattern,
    "cpu_percent": int(cpu),
    "memory_high_bytes": int(memory_high),
    "memory_max_bytes": int(memory_max),
    "container_id": container_id,
}
Path(path).write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
PY
}

run_scenario() {
    local scenario="$1"
    local cpu="$2"
    local memory_high="$3"
    local memory_max="$4"
    local pattern="$5"
    local scenario_dir="$OUTPUT_DIR/${scenario}-${pattern}"
    local container_id=""
    local runner_pid=""
    local cgroup_path=""

    mkdir -p "$scenario_dir"
    echo "==> Running $scenario / $pattern"

    java_cmd run --net --memory "$memory_max" --cpu "$cpu" "$ROOTFS_DIR" \
        /bin/busybox httpd -f -p 8080 -h /tmp/www \
        >"$scenario_dir/container-parent.stdout" \
        2>"$scenario_dir/container-parent.stderr" &
    runner_pid="$!"
    ACTIVE_RUNNER_PID="$runner_pid"

    container_id="$(wait_for_container_id "$scenario_dir/container-parent.stderr" "$runner_pid")"
    ACTIVE_CONTAINER_ID="$container_id"
    cgroup_path="/sys/fs/cgroup/jcontainer/$container_id"
    if [[ ! -d "$cgroup_path" ]]; then
        echo "Expected cgroup path not found: $cgroup_path" >&2
        exit 1
    fi

    echo "$memory_high" > "$cgroup_path/memory.high"
    write_metadata "$scenario_dir/metadata.json" "$scenario" "$pattern" "$cpu" "$memory_high" "$memory_max" "$container_id"

    wait_for_http "http://10.0.0.2:8080/"

    python3 "$SCRIPT_DIR/sample-cgroup.py" \
        --cgroup-path "$cgroup_path" \
        --duration-seconds "$((DURATION_SECONDS + 1))" \
        --interval-seconds 1 \
        --output-jsonl "$scenario_dir/cgroup-samples.jsonl" &
    local sampler_pid="$!"

    python3 "$SCRIPT_DIR/run-http-load.py" \
        --url "http://10.0.0.2:8080/" \
        --pattern "$pattern" \
        --duration-seconds "$DURATION_SECONDS" \
        --base-rps "$BASE_RPS" \
        --timeout-ms "$REQUEST_TIMEOUT_MS" \
        --trace-jsonl "$scenario_dir/probe-trace.jsonl" \
        --summary-json "$scenario_dir/load-summary.json"

    wait "$sampler_pid"

    local registry_dir="$HOME/.jcontainer/containers/$container_id"
    if [[ -d "$registry_dir" ]]; then
        cp -a "$registry_dir" "$scenario_dir/container-registry"
    fi

    cleanup_container "$container_id" "$runner_pid"
    ACTIVE_CONTAINER_ID=""
    ACTIVE_RUNNER_PID=""
}

if [[ "$(uname -s)" != "Linux" ]]; then
    echo "Baseline capture requires Linux with cgroup v2 and network namespaces." >&2
    exit 1
fi
if [[ "${EUID:-$(id -u)}" -ne 0 ]]; then
    echo "Run as root: sudo scripts/capture-baselines.sh" >&2
    exit 1
fi

require_command java
require_command mvn
require_command python3
require_command curl
require_command ip
require_command nsenter

if [[ ! -f /sys/fs/cgroup/cgroup.controllers ]]; then
    echo "Missing cgroup v2 root at /sys/fs/cgroup" >&2
    exit 1
fi

if [[ "$SKIP_BUILD" -eq 0 ]]; then
    (cd "$PROJECT_DIR" && mvn -q -DskipTests package)
fi
if [[ ! -f "$JAR_PATH" ]]; then
    echo "Missing jar: $JAR_PATH" >&2
    exit 1
fi

if [[ ! -d "$ROOTFS_DIR" || -z "$(ls -A "$ROOTFS_DIR" 2>/dev/null)" ]]; then
    "$SCRIPT_DIR/setup-rootfs.sh"
fi
if [[ ! -x "$ROOTFS_DIR/bin/busybox" ]]; then
    echo "Expected busybox at $ROOTFS_DIR/bin/busybox" >&2
    exit 1
fi

mkdir -p "$ROOTFS_DIR/tmp/www" "$OUTPUT_DIR"
echo "OK" > "$ROOTFS_DIR/tmp/www/index.html"

IFS=',' read -r -a PATTERN_LIST <<< "$PATTERNS"

cat > "$OUTPUT_DIR/run-config.json" <<EOF
{
  "duration_seconds": $DURATION_SECONDS,
  "base_rps": $BASE_RPS,
  "request_timeout_ms": $REQUEST_TIMEOUT_MS,
  "patterns": "$PATTERNS"
}
EOF

for pattern in "${PATTERN_LIST[@]}"; do
    case "$pattern" in
        steady|spike|oscillating) ;;
        *) echo "Unsupported pattern: $pattern" >&2; exit 1 ;;
    esac
    run_scenario "small" 25 67108864 134217728 "$pattern"
    run_scenario "medium" 50 134217728 268435456 "$pattern"
    run_scenario "large" 100 268435456 536870912 "$pattern"
done

python3 "$SCRIPT_DIR/summarize-baseline-report.py" --output-dir "$OUTPUT_DIR"

echo
echo "Baseline capture complete:"
echo "  $OUTPUT_DIR"
echo "  $OUTPUT_DIR/summary.md"
echo "  $OUTPUT_DIR/summary.json"

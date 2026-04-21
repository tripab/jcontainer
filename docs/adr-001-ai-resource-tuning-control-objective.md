# ADR-001: AI Resource Tuning — Control Objective and Evaluation Baseline

**Status**: Accepted  
**Date**: 2026-04-21

---

## Context

JContainer is a single-host container runtime. Phase 2 added cgroup v2 resource limits (`--memory`, `--cpu`), but limits are fixed at container start and never adjusted at runtime. This means:

- An operator must guess the right allocation upfront.
- Workloads with bursty or variable traffic are over-provisioned at rest or throttled during spikes.
- There is no feedback loop between observed service behavior and the resource allocation.

The goal of the AI resource tuning feature is to close this loop with a parent-side controller that observes the running container and adjusts cgroup limits online.

This ADR records the control objective, the evaluation baseline, and the definition of "better" that will be used throughout development and evaluation.

---

## Decision

### Target Workload

The evaluation baseline uses:

- **Service**: a toy HTTP server (`ToyHttpService`) running inside the container, responding to `GET /` with 200 OK and configurable simulated processing latency.
- **Load generator**: a host-side burst load generator (`BurstLoadGenerator`) that drives three traffic patterns against the container's network address (`10.0.0.2:8080` under the `NetworkManager` veth model).

Three traffic patterns are used:

| Pattern | Description |
|---|---|
| `STEADY` | Constant request rate throughout the run |
| `SPIKE` | 5× the base rate for the middle 20% of the run |
| `OSCILLATING` | Sinusoidal variation between ~0 and 2× the base rate |

### SLO Metrics (Service-Level Objective)

| Metric | Definition | Target |
|---|---|---|
| p95 latency | 95th-percentile end-to-end request latency | < 200 ms |
| Timeout rate | Fraction of requests that exceed the probe timeout | < 1% |
| Success rate | Fraction of requests that return HTTP 200 | > 99% |

The p95 latency and timeout rate are the primary SLO signals. The controller uses p95 as the main optimization target.

### Resource-Efficiency Metrics

| Metric | Definition |
|---|---|
| Average CPU quota | Mean `cpu.max` quota applied across the run |
| Peak memory assigned | Maximum `memory.high` value applied during the run |
| Throttling rate | Fraction of CPU scheduling periods that were throttled (`nr_throttled / nr_periods` from `cpu.stat`) |
| Memory pressure events | Count of `high` events from `memory.events` (reclaim pressure crossings) |
| Time in each bundle | Seconds spent at each resource level |

### Resource Bundles

Three fixed resource bundles define the discrete search space for the controller. All values are concrete and monotonically ordered.

| Bundle | CPU quota | `memory.high` | `memory.max` |
|---|---|---|---|
| `small` | 25% (1 core × 25%) | 64 MiB | 128 MiB |
| `medium` | 50% | 128 MiB | 256 MiB |
| `large` | 100% | 256 MiB | 512 MiB |

CPU quota is expressed as a percentage of one core and maps to `cpu.max` as `$QUOTA $PERIOD` (quota = percent × 1000 µs, period = 100 000 µs).

The controller always starts at `medium`. Exploration begins only after the warmup grace period has elapsed.

### Evaluation Baseline

Before the adaptive controller is active, three fixed-resource baseline runs are performed:

1. Fixed `small` bundle for the full run.
2. Fixed `medium` bundle for the full run.
3. Fixed `large` bundle for the full run.

Each run uses the `SPIKE` traffic pattern (the hardest case for a fixed allocation) for 60 seconds at a base rate of 10 RPS. Results are recorded in a summary table.

| Scenario | p95 Latency | Timeout Rate | Avg CPU | Avg Memory | Pressure Events |
|---|---|---|---|---|---|
| Fixed small | — | — | 25% | 64 MiB | — |
| Fixed medium | — | — | 50% | 128 MiB | — |
| Fixed large | — | — | 100% | 256 MiB | — |
| Autotune | — | — | — | — | — |

The `Autotune` row is filled in during Phase 6.

### Definition of "Better"

The autotune controller is considered to have improved over the fixed medium baseline if **either** of the following holds after a full evaluation run:

1. **Equal or better SLO at lower average resource allocation** — p95 latency ≤ medium baseline and timeout rate ≤ medium baseline, while average CPU quota or peak memory is measurably lower.
2. **Materially better SLO at similar resource spend** — average CPU quota and peak memory within 10% of the medium baseline, while p95 latency or timeout rate is meaningfully improved (≥ 20% reduction).

Beating the fixed `large` baseline on SLO is not a goal; the large bundle is a safety ceiling, not a competition target.

---

## Consequences

- All phases from Phase 1 onward are evaluated against this control objective.
- The three bundle constants (`small`, `medium`, `large`) are fixed for v1. Adding more bundles is future work.
- The probe target is `10.0.0.2:8080` and requires `--net` to be set.
- Baseline traces (captured on Linux with cgroup v2) are used in Phase 6 to fill in the summary table above.
- The p95 SLO threshold (200 ms) is treated as a configuration value in `AutotuneConfig` rather than a hard-coded constant, so it can be tuned per deployment.

---

## Alternatives Considered

**Continuous optimization (e.g., gradient-based CPU/memory tuning)**: rejected because it is harder to bound, harder to test deterministically, and ill-suited to the small data volumes seen in a single-host run.

**Deep reinforcement learning**: rejected for the same reasons, and because it requires a training infrastructure that is out of scope for a single-host runtime.

**TCP connect-only probing**: deferred. HTTP probing gives richer signal (latency distribution, status codes) with almost no added complexity given that the toy service is HTTP-native.

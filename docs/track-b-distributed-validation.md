# Track B Distributed Validation Notes

Status: Draft
Date: 2026-06-01

This note captures the optional distributed validation plan for Track B. It is not required for the core implementation. The in-repo feature remains a single-host control loop with local harness tests.

## Why Distributed Validation Is Separate

The local implementation can verify:

- cgroup v2 controls are updated by the parent process
- probes and telemetry are collected in a repeatable control loop
- safety rules override controller choices
- the local experiment harness compares fixed bundles with autotune

It cannot prove cluster-level cost savings or behavior under realistic multi-service contention. Those claims require a separate distributed experiment and should not block the core runtime feature.

## Minimum Credible Setup

Use four small EC2 instances in one region and one availability zone:

| Role | Count | Purpose |
|------|-------|---------|
| Control-plane node | 1 | Run orchestration, collect logs, and coordinate experiments |
| Worker nodes | 2 | Run service containers with fixed-bundle and autotune configurations |
| Load/metrics node | 1 | Generate traffic and collect probe, cgroup, and host metrics |

Recommended starting shape:

- Region: `us-east-1` for lowest common pricing and broad instance availability
- OS: Linux/Unix
- Instance family: `t3.small` for short smoke validation, `t3.medium` if the toy service or metrics collection becomes noisy
- Storage: minimal gp3 root volumes are sufficient for short runs
- Networking: private subnet or security-group restricted public access; expose only SSH and experiment ports needed for the run

## Cost Envelope

Prices change by region and date. Re-check the AWS Pricing Calculator or EC2 On-Demand pricing page before running an experiment:

- AWS EC2 On-Demand pricing: https://aws.amazon.com/ec2/pricing/on-demand/
- AWS T3 instance page: https://aws.amazon.com/ec2/instance-types/t3/
- AWS Pricing Calculator: https://calculator.aws/

As of 2026-06-01, the AWS T3 page lists Linux/Unix On-Demand prices for US East (N. Virginia) at approximately:

| Instance | Approx. hourly price | 4 nodes for 72h | 4 nodes for 7d |
|----------|----------------------|-----------------|----------------|
| `t3.small` | `$0.0209/hr` | `$6.02` | `$14.04` |
| `t3.medium` | `$0.0418/hr` | `$12.04` | `$28.09` |

Allow extra budget for EBS, snapshots, data transfer, CloudWatch logs, and idle resources. A disciplined 72-hour validation should stay comfortably under `$50`; a week-long run should still be modest if instances are stopped immediately afterward.

## Experiment Matrix

Run the same workload profile as the local harness:

| Scenario | Load pattern | Duration | Required output |
|----------|--------------|----------|-----------------|
| Fixed small | steady, spike, oscillating | 60s each | latency, timeout, CPU, memory, pressure, bundle time |
| Fixed medium | steady, spike, oscillating | 60s each | latency, timeout, CPU, memory, pressure, bundle time |
| Fixed large | steady, spike, oscillating | 60s each | latency, timeout, CPU, memory, pressure, bundle time |
| Autotune | steady, spike, oscillating | 60s each | latency, timeout, CPU, memory, pressure, bundle time, decision logs |

Use at least three repetitions per scenario before making any claim. Report median and worst run for p95 latency and timeout rate.

## Evidence Needed

The distributed run should produce:

- structured autotune decision logs
- cgroup file snapshots before and after bundle changes
- load generator summaries for each scenario
- report tables matching the local `ExperimentHarness` shape
- notes on kernel version, cgroup v2 status, PSI availability, and instance type

## What Still Cannot Be Proven

Even this distributed setup does not prove:

- Kubernetes scheduler interactions
- long-horizon learning stability over days or weeks
- generalization to heterogeneous production services
- fleet-level or organization-level cost savings
- behavior under noisy neighbor workloads outside the experiment account

Those require a production-like cluster, real service traces, and a longer observation window.


# JContainer

JContainer is an educational container runtime written in Java 25. It builds a
Linux container from the same kernel primitives used by larger runtimes, while
keeping the implementation small enough to read end to end. Native operations
are called through the Java Foreign Function & Memory (FFM) API.

The repository now has three layers:

| Layer | What it demonstrates | Main entry point |
|---|---|---|
| Core container | Linux namespaces, `pivot_root`, `/proc`, native `execvp`, cgroups, networking, OCI images, and lifecycle state | `run` |
| Track A: least-privilege seccomp | Profile a workload, generate a syscall allowlist, and enforce it at the payload boundary | `profile`, `run --seccomp-policy` |
| Track B: AI-inspired resource tuning | Observe cgroup and service telemetry, then adapt CPU and memory bundles online with an epsilon-greedy controller and safety overrides | `run --autotune-config` |

Linux is the primary platform and the only platform with full isolation,
seccomp, networking, cgroups, and autotuning. macOS has a deliberately degraded,
experimental `chroot` mode for basic development only.

## How the core container works

For a command such as `run rootfs /bin/sh`, the runtime follows this path:

1. `ContainerParent` parses the CLI, optionally pulls an OCI image, prepares a
   cgroup and network, and starts the internal child command.
2. On Linux, the child is launched with `unshare --mount --uts --pid [--net]
   --fork --propagation private`. Namespace creation stays in the child launcher;
   the Java parent remains outside the mount namespace it will later pivot.
3. `ContainerChild` prepares any seccomp filter while the host classpath is
   still reachable, sets the hostname, and enters the container filesystem.
4. `LinuxRuntime` bind-mounts the rootfs, calls `pivot_root`, mounts `/proc`, and
   detaches the old root.
5. The prepared seccomp filter is installed, if requested, and native `execvp`
   replaces the Java child with the payload. The payload becomes PID 1 in the
   container PID namespace.
6. The parent captures logs, records lifecycle state under
   `~/.jcontainer/containers/`, and cleans up cgroup and network resources.

This is intentionally a small runtime, not an OCI runtime-spec implementation or
a production security boundary.

## Capabilities

| Capability | Linux | macOS |
|---|---|---|
| Filesystem isolation | Bind mount + `pivot_root` | `chroot` only |
| PID, mount, and UTS namespaces | Yes | No |
| Network namespace and veth pair | Optional with `--net` | No |
| Static CPU and memory limits | cgroups v2 | No |
| OCI image pull and layer extraction | Yes | Yes, subject to host/rootfs compatibility |
| Container lifecycle and logs | Yes | Yes |
| Profile-guided seccomp | Yes | Explicitly rejected |
| Adaptive resource tuning | Yes, cgroups v2 required | Explicitly rejected |

## Quick start

### Requirements

- Java 25 and Maven
- Linux with root privileges for full container execution
- `unshare` from util-linux
- `ip` and `nsenter` from iproute2/util-linux for `--net`
- `strace` for seccomp profiling
- cgroup v2 for resource limits and Track B
- `curl` or `wget` for rootfs setup and image-related workflows

The bundled Linux syscall tables support `x86_64` and `aarch64`.

### Build and prepare a rootfs

The Maven jar is intentionally thin, so build a runtime classpath containing its
dependencies:

```bash
mvn clean package
mvn -q dependency:build-classpath -Dmdep.outputFile=target/runtime-classpath.txt

export JCONTAINER_CP="target/jcontainer-1.0-SNAPSHOT.jar:$(cat target/runtime-classpath.txt)"

./scripts/setup-rootfs.sh
```

For shorter examples, define this shell function from the repository root:

```bash
jcontainer() {
  sudo java --enable-native-access=ALL-UNNAMED \
    -cp "$JCONTAINER_CP" \
    org.jcontainer.JContainer "$@"
}
```

Run a shell in the Alpine rootfs:

```bash
jcontainer run rootfs /bin/sh
```

Payload executable paths must be absolute inside the container.

### Other core workflows

```bash
# Static resource limits: 256 MiB and 50% of one CPU
jcontainer run --memory 256m --cpu 50 rootfs /bin/sh

# Add a network namespace and veth pair
jcontainer run --net rootfs /bin/sh

# Pull and run an OCI image instead of supplying rootfs/
jcontainer run --image alpine:latest /bin/sh

# Inspect lifecycle state and logs (use the same privilege identity as run)
jcontainer list
jcontainer logs <container-id>
jcontainer stop <container-id>
jcontainer rm <container-id>
```

## Track A: least-privilege seccomp

Track A adds profile-guided syscall policy generation and Linux seccomp
enforcement. It is a repo-scale adaptation of dynamic policy-generation ideas
from [BEACON](https://arxiv.org/abs/2512.00414) and programmable syscall security
research. JContainer uses `strace` for observation and classic seccomp BPF for
enforcement instead of reproducing those larger systems.

### Profile and enforce a workload

Generate a same-architecture JSON policy from a representative run:

```bash
mkdir -p policies

jcontainer profile \
  --output policies/echo.json \
  rootfs /bin/echo hello
```

Run the workload with that policy:

```bash
jcontainer run \
  --seccomp-policy policies/echo.json \
  rootfs /bin/echo hello
```

Merge observations from another representative path into the same policy:

```bash
jcontainer profile \
  --append \
  --output policies/echo.json \
  rootfs /bin/echo another-input
```

The generated policy records the observed payload syscalls and defaults to
`ERRNO(EPERM)` for everything else. Policies are versioned, architecture-specific,
and validated against bundled syscall tables. The parser identifies the final
payload `execve` boundary and follows only payload descendants, avoiding JVM worker
thread noise in the allowlist.

Policy preparation happens before `pivot_root`, while JSON dependencies and
syscall resources are available. Kernel enforcement happens after filesystem
setup and immediately before the native exec handoff. The runtime adds `execve`
and `futex` to the installed filter as JVM-to-payload bridge requirements; they
are not added to the saved workload profile.

One profile run is rarely enough for a real application. Exercise representative
startup, steady-state, error, and shutdown paths and merge them with `--append`.
Track A does not yet implement argument-aware rules, seccomp user notification,
Linux capability dropping, or full `runc` policy compatibility.

## Track B: AI-inspired resource tuning

Track B is a single-host adaptive cgroup controller inspired by recent
cloud-native autoscaling work, including
[multi-agent autoscaling research](https://arxiv.org/abs/2505.21559) and
[Ursa](https://arxiv.org/abs/2401.02920). It deliberately replaces cluster-scale
or deep-RL machinery with an understandable epsilon-greedy controller over a
small set of resource bundles.

The parent-side loop combines:

- cgroup v2 CPU, memory, event, and pressure telemetry
- repeated HTTP health/latency probes into the container network namespace
- discrete CPU, `memory.high`, and `memory.max` bundles
- an SLO-aware reward and epsilon-greedy bundle selector
- readiness gates, cooldowns, OOM handling, and safety overrides
- one structured JSON decision record on stderr per control cycle

Autotuning requires Linux, writable cgroup v2 controls, `--net`, and an HTTP
service reachable at the default container address `10.0.0.2`.

### Example configuration

```json
{
  "controlInterval": "1s",
  "probe": {
    "mode": "http",
    "host": "10.0.0.2",
    "port": 8080,
    "path": "/",
    "timeout": "250ms"
  },
  "bundles": [
    {
      "name": "small",
      "cpuPercent": 25,
      "memoryHighBytes": 67108864,
      "memoryMaxBytes": 134217728
    },
    {
      "name": "medium",
      "cpuPercent": 50,
      "memoryHighBytes": 134217728,
      "memoryMaxBytes": 268435456
    },
    {
      "name": "large",
      "cpuPercent": 100,
      "memoryHighBytes": 268435456,
      "memoryMaxBytes": 536870912
    }
  ],
  "slo": {
    "p95LatencyMillis": 200,
    "maxTimeoutRate": 0.01
  },
  "bandit": {
    "epsilon": 0.2,
    "minEpsilon": 0.05,
    "cooldownCycles": 3
  },
  "safety": {
    "consecutiveSloMisses": 3,
    "oomFreezeCycles": 5
  }
}
```

Start a simple BusyBox HTTP service with the controller:

```bash
mkdir -p rootfs/tmp/www
printf 'OK\n' > rootfs/tmp/www/index.html

jcontainer run \
  --net \
  --autotune-config autotune.json \
  rootfs /bin/busybox httpd -f -p 8080 -h /tmp/www
```

The normal loop changes `cpu.max` and `memory.high`; `memory.max` is treated as a
coarser emergency boundary. If probe readiness or safety checks fail, exploration
is frozen and the safety policy can retain or select a larger fallback bundle.

### Baseline evaluation

The repository includes Linux-only scripts for repeatable small, medium, and
large fixed-resource baselines under steady, spike, and oscillating HTTP load:

```bash
sudo scripts/capture-baselines.sh \
  --duration-seconds 60 \
  --base-rps 10
```

Artifacts are written under `baseline-runs/<timestamp>/`, including cgroup
samples, probe traces, load summaries, and Markdown/JSON reports. To regenerate
the summary for an existing run:

```bash
python3 scripts/summarize-baseline-report.py \
  --output-dir baseline-runs/<timestamp>
```

Track B is an experimental controller for learning and evaluation, not a claim of
general optimality or a replacement for a production autoscaler.

## Code map

```text
src/main/java/org/jcontainer/
  JContainer.java                 CLI dispatch
  ContainerParent.java           Parent orchestration and lifecycle
  ContainerChild.java            Isolated child setup and payload handoff
  ContainerConfig.java           run command parsing
  ImageManager.java              OCI image pull/cache orchestration
  CgroupManager.java             cgroup v2 controls and telemetry reads
  NetworkManager.java            Network namespace and veth setup

  ContainerProfiler.java         Track A profile workflow
  StraceRunner.java              Workload tracing
  StraceParser.java              Payload-boundary and descendant attribution
  SeccompPolicy.java             Versioned JSON policy
  SeccompFilterBuilder.java      Classic BPF allowlist generation

  AutotuneLoop.java              Track B control-loop orchestration
  BanditController.java          Epsilon-greedy decision engine
  SafetyGuard.java               SLO and OOM safety overrides
  TelemetryCollector.java        cgroup and PSI observations
  ProbeAgent.java                Host-side HTTP observations

src/main/java/org/jcontainer/runtime/
  ContainerRuntime.java          Platform abstraction
  LinuxRuntime.java              Linux namespace/rootfs/seccomp/exec path
  MacOSRuntime.java              Degraded chroot path
  Syscalls.java                  FFM native bindings
  PreparedSeccompFilter.java     Pre-pivot native filter lifetime

scripts/
  setup-rootfs.sh                Alpine minirootfs setup for Linux
  setup-rootfs-macos.sh          Experimental macOS chroot rootfs setup
  capture-baselines.sh           Fixed-resource Track B experiments
  run-http-load.py               HTTP load generation
  sample-cgroup.py               cgroup telemetry capture
```

## Testing

The default suite is platform-independent and does not require root:

```bash
mvn test
```

The integration profile exercises namespaces, `pivot_root`, PID 1 handoff,
seccomp profiling/enforcement, and command behavior. Run it on Linux with a
populated rootfs and root privileges:

```bash
./scripts/setup-rootfs.sh
sudo mvn verify -Pintegration
```

The `integration/track-a-track-b-merge` branch has been verified with both the
default and integration profiles on Linux.

## Scope and safety

JContainer directly changes namespaces, mounts, cgroups, routes, and seccomp
state. Run privileged experiments on a disposable Linux VM until you understand
the code paths and cleanup behavior. The runtime does not yet provide the full
hardening expected from production container engines, including comprehensive
capability management, user namespaces, LSM integration, OCI runtime-spec
compliance, or a daemon-level trust boundary.

The original core implementation was inspired by the
[InfoQ article on building a container in Go](https://www.infoq.com/articles/build-a-container-golang/).

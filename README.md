# JContainer: A Basic Container in Java

## Overview

Reimplement the container from the [InfoQ Go article](https://www.infoq.com/articles/build-a-container-golang/) in idiomatic Java 25 using the Foreign Function & Memory (FFM) API for native syscalls. Supports **Linux** (full isolation) and **macOS** (degraded chroot-based mode for development).

The Go implementation creates a minimal container by:
1. Re-executing itself in new Linux namespaces (UTS, PID, MNT) via `clone()` flags
2. In the child: bind-mounting a rootfs, calling `pivot_root`, then exec-ing the target command

---

## Cross-Platform Strategy

Containers are fundamentally a Linux kernel technology (namespaces, cgroups, pivot_root). macOS lacks these primitives. Our approach:

| Capability | Linux | macOS |
|---|---|---|
| Filesystem isolation | `pivot_root` via FFM | `chroot` via FFM |
| PID namespace | `unshare --pid` | Not available (shared PID space) |
| Mount namespace | `unshare(CLONE_NEWNS)` via FFM | Not available |
| UTS namespace | `unshare(CLONE_NEWUTS)` via FFM | Not available |
| Hostname | `sethostname()` via FFM | Skipped (would affect host) |
| /proc mount | `mount("proc", ...)` via FFM | Skipped |
| Resource limits | Phase 2: cgroups v2 | Not available |
| Seccomp syscall filtering | `prctl(PR_SET_SECCOMP)` with classic BPF | Not available; rejected explicitly |

**Implementation**: A `ContainerRuntime` interface with `LinuxRuntime` and `MacOSRuntime` implementations. Platform detected at startup. Both use FFM for their respective syscalls — `chroot(2)` and `chdir(2)` are available on macOS via the same FFM `Linker.nativeLinker()` mechanism.

---

## Phase 1: Core Container (Replicate Go Article)

### Architecture

**Two-mode dispatch** (matching Go's pattern):
- `java JContainer run <rootfs> <cmd> [args...]` — parent mode: spawns child in isolated environment
- `java JContainer child <rootfs> <cmd> [args...]` — child mode: sets up container filesystem, execs command

**Namespace creation (Linux only):**
Java's `ProcessBuilder` doesn't support Linux clone flags. We use a hybrid approach:
- **`unshare(2)` via FFM** in the parent to create new UTS + MNT namespaces
- **Linux `unshare` command** as a wrapper for PID namespace (since `unshare(CLONE_NEWPID)` only affects children, not the caller — we need the forked child to be PID 1)

**macOS mode:**
The parent simply re-invokes itself with "child" via `ProcessBuilder` (no namespace wrapping). The child uses `chroot()` via FFM for filesystem isolation.

## Phase 2: Enhancements (Toward Production-Like)

Five features, each independently implementable:

### Feature 1: Cgroups v2 Resource Limits (Linux only)
- Write to `/sys/fs/cgroup/` to create a cgroup for the container
- Set `memory.max`, `cpu.max` limits
- Add container PID to `cgroup.procs`
- Clean up cgroup on container exit
- New class: `CgroupManager.java`
- User-facing: `run --memory 100m --cpu 50 rootfs /bin/sh`
- macOS: ignored with warning

### Feature 2: Network Namespace with veth Pair (Linux only)
- Add `CLONE_NEWNET` to namespace flags
- Create a veth pair (host side + container side) using `ip` commands
- Assign IP addresses, set up routing
- Enable container-to-host connectivity
- New class: `NetworkSetup.java`
- macOS: skipped

### Feature 3: OCI Image Support (Cross-platform)
- Download container images from Docker Hub as OCI tarballs
- Extract layers into a rootfs directory
- Support `run --image alpine:latest /bin/sh` instead of requiring a pre-made rootfs
- Uses Java's `HttpClient` + tar/gzip extraction
- New class: `ImageManager.java`
- Works on both Linux and macOS

### Feature 4: Container Lifecycle (Cross-platform)
- Assign container IDs (short random hex)
- Store container state in a state directory
- Commands: `list` (show running containers), `stop <id>`, `logs <id>`
- Capture container stdout/stderr to log files
- New class: `ContainerRegistry.java`
- Works on both Linux and macOS

### Feature 5: Least-Privilege Seccomp Profiles (Linux only)
- Generate syscall allowlist policies with `profile --output <file> <rootfs> <cmd> [args...]`
- Merge additional profiling runs with `profile --append --output <file> ...`
- Enforce a policy with `run --seccomp-policy <file> <rootfs> <cmd> [args...]`
- Store seccomp policy path and digest in container lifecycle metadata
- macOS rejects profiling and seccomp enforcement explicitly instead of silently ignoring them

---

## Project Structure

```
denver/
├── pom.xml
├── src/main/java/org/jcontainer/
│   ├── JContainer.java              # Entry point, run/profile/child dispatch
│   ├── ContainerParent.java          # Parent process logic
│   ├── ContainerChild.java           # Child process logic
│   ├── ContainerProfiler.java        # strace-based seccomp policy generation
│   ├── SeccompPolicy.java            # Policy JSON model, validation, digest
│   └── runtime/
│       ├── ContainerRuntime.java     # Interface for platform-specific ops
│       ├── LinuxRuntime.java         # Linux: namespaces, pivot_root, mount, seccomp
│       ├── MacOSRuntime.java         # macOS: chroot, limited isolation
│       ├── Syscalls.java             # FFM bindings (cross-platform)
│       └── LinuxConstants.java       # Linux-specific constants
├── src/test/java/org/jcontainer/
│   ├── JContainerTest.java           # Entry point argument parsing tests
│   ├── ContainerParentTest.java      # Parent command-building tests
│   ├── runtime/
│   │   ├── LinuxConstantsTest.java   # Constant value correctness
│   │   ├── LinuxRuntimeTest.java     # Linux command construction tests
│   │   └── MacOSRuntimeTest.java     # macOS command construction tests
│   └── integration/
│       └── ContainerIntegrationTest.java  # End-to-end container tests
├── scripts/
│   ├── setup-rootfs.sh              # Downloads Alpine miniroot (Linux)
│   └── setup-rootfs-macos.sh        # Creates a minimal macOS-compatible rootfs for chroot testing
└── rootfs/                           # (gitignored) container root filesystem
```


## Build & Run

```bash
# Build
mvn clean package

# Setup rootfs (Linux)
./scripts/setup-rootfs.sh
# Setup rootfs (macOS — builds a host-binary chroot rootfs)
./scripts/setup-rootfs-macos.sh

# Run — Linux (full isolation)
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer run rootfs /bin/sh

# Profile a workload and write a seccomp policy — Linux only
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer profile --output /tmp/echo-policy.json rootfs /bin/echo hello

# Run with a generated seccomp policy — Linux only
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer run --seccomp-policy /tmp/echo-policy.json rootfs /bin/echo hello

# Run — macOS (chroot-only, limited isolation)
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer run rootfs /bin/sh

# Run tests
mvn test                    # Unit tests
sudo mvn verify -Pintegration # Integration tests (requires rootfs + root; Linux profile tests also require strace)
```

### Track B Baseline Capture (Linux)

The AI resource tuning baseline capture is automated for Linux/cgroup v2 hosts:

```bash
sudo scripts/capture-baselines.sh
```

The script builds the jar, creates `rootfs/` if needed, launches fixed `small`, `medium`, and `large` containers, drives `steady`, `spike`, and `oscillating` HTTP load against `10.0.0.2:8080`, samples cgroup files, and writes request traces plus reports under `baseline-runs/<timestamp>/`.

Useful shorter smoke run:

```bash
sudo scripts/capture-baselines.sh --duration-seconds 10 --base-rps 2 --patterns spike
```

Key outputs:

- `summary.md` — Markdown comparison table
- `summary.json` — machine-readable aggregate report
- `<bundle>-<pattern>/probe-trace.jsonl` — per-request probe trace
- `<bundle>-<pattern>/cgroup-samples.jsonl` — sampled cgroup telemetry/control files

---

## Testing

### Unit Tests (run on any platform, no root required)

#### `JContainerTest.java`
- **`testRunModeDispatch`**: Verify "run" arg routes to `ContainerParent`
- **`testChildModeDispatch`**: Verify "child" arg routes to `ContainerChild`
- **`testUnknownModeExits`**: Verify unknown arg prints usage and exits with code 1
- **`testInsufficientArgsExits`**: Verify too few args prints usage and exits with code 1
- **`testRuntimeSelectionLinux`**: When `os.name` is "Linux", a `LinuxRuntime` is selected
- **`testRuntimeSelectionMacOS`**: When `os.name` is "Mac OS X", a `MacOSRuntime` is selected

#### `ContainerParentTest.java`
- **`testJavaPathResolution`**: Verify Java binary path is resolved from `ProcessHandle`
- **`testClasspathResolution`**: Verify classpath is read from system property
- **`testChildCommandContainsAllArgs`**: Verify the spawned command includes rootfs path and user command

#### `LinuxConstantsTest.java`
- **`testMsBind`**: `MS_BIND == 4096`
- **`testMsRec`**: `MS_REC == 16384`
- **`testMsPrivate`**: `MS_PRIVATE == (1 << 18)`
- **`testCloneNewuts`**: `CLONE_NEWUTS == 0x04000000`
- **`testCloneNewns`**: `CLONE_NEWNS == 0x00020000`
- **`testCloneNewpid`**: `CLONE_NEWPID == 0x20000000`
- **`testMntDetach`**: `MNT_DETACH == 2`

#### `LinuxRuntimeTest.java`
- **`testBuildChildCommandStructure`**: Verify command starts with `unshare --pid --fork` followed by java path, flags, classpath, main class, "child", rootfs, and user command
- **`testBuildChildCommandPreservesUserArgs`**: Verify all user-provided command arguments appear at the end
- **`testBuildChildCommandIncludesNativeAccess`**: Verify `--enable-native-access=ALL-UNNAMED` is present

#### `MacOSRuntimeTest.java`
- **`testBuildChildCommandNoUnshare`**: Verify command does NOT contain `unshare`
- **`testBuildChildCommandStructure`**: Verify command is just `[javaPath, flags, -cp, classpath, mainClass, "child", rootfs, ...cmd]`
- **`testSetupParentIsNoOp`**: Verify `setupParent()` completes without error (and prints a warning)

#### Seccomp and profiling tests
- **`SeccompPolicyTest.java`**: Verify JSON round trips, stable digest computation, architecture checks, syscall validation, and deterministic normalization
- **`SeccompFilterBuilderTest.java`**: Verify classic BPF arch guards, deny path, allowlist checks, bootstrap `execve` injection, and oversized policy rejection
- **`SeccompManagerTest.java`**: Verify `no_new_privs` and `PR_SET_SECCOMP` installation behavior and install-time error messages
- **`ContainerProfilerTest.java`**: Verify profile orchestration, deterministic profile output, `--append` merging, image rootfs resolution, and nonzero workload failure handling
- **`StraceParserTest.java`**: Verify setup-noise discard, descendant trace parsing, and missing final payload `execve` failures

### Integration Tests (require rootfs, root privileges, platform-specific)

#### `ContainerIntegrationTest.java`
Annotated with `@Tag("integration")`, skipped by default (enabled via Maven failsafe plugin or `-Pintegration` profile).

**Platform-conditional tests** (use JUnit `@EnabledOnOs`):

- **`testContainerSeesIsolatedFilesystem`** (Linux + macOS): Run `ls /` inside container, verify output matches rootfs contents and not host root
- **`testContainerHostname`** (Linux only): Run `hostname` inside container, verify output is "container"
- **`testContainerPidNamespace`** (Linux only): Run `cat /proc/1/cmdline` inside container, verify PID 1 is the launched command (not host init)
- **`testContainerExitCode`** (Linux + macOS): Run `exit 42` and verify the parent process gets exit code 42
- **`testContainerStdout`** (Linux + macOS): Run `echo hello` and capture stdout, verify "hello" appears
- **`testMacOSWarning`** (macOS only): Capture stderr, verify isolation-limited warning is printed
- **`testLinuxProfileGeneratesEchoPolicy`** (Linux only): Run `profile` for `/bin/echo`, validate that a policy file is written, and verify profile report output
- **`testLinuxRunWithGeneratedSeccompPolicySucceedsForSameWorkload`** (Linux only): Profile `/bin/echo`, run `/bin/echo` under the generated policy, and verify it succeeds
- **`testLinuxGeneratedPolicyRejectsIncompatibleWorkload`** (Linux only): Profile `/bin/true`, run `/bin/echo` under that policy, and verify seccomp denial context
- **`testLinuxSeccompPolicyDeniesIncompleteWorkload`** (Linux only): Attach a deliberately incomplete policy and verify the denied workload fails
- **`testMacOSProfileFailsUnsupported`** (macOS only): Verify `profile` fails with an unsupported-feature message
- **`testMacOSRunWithSeccompPolicyFailsUnsupported`** (macOS only): Verify `run --seccomp-policy` fails with an unsupported-feature message

---


## Verification Plan

1. **Build**: `mvn clean package` succeeds on both Linux and macOS
2. **Unit tests**: `mvn test` passes on both platforms (no root required)
3. **Rootfs**: setup script creates a usable rootfs
4. **Linux full test**: `sudo java ... JContainer run rootfs /bin/sh`
    - `hostname` → "container"
    - `ps aux` → PID 1 is the shell
    - `ls /` → Alpine rootfs, not host
    - `exit` → clean return to host
5. **macOS chroot test**: `sudo java ... JContainer run rootfs /bin/sh`
    - `ls /` → macOS test rootfs contents, not host root
    - `exit` → clean return to host
    - Warning printed about limited isolation
6. **Integration tests**: `sudo mvn verify -Pintegration` passes on respective platforms
7. **Seccomp smoke test**:
    - `profile --output /tmp/echo-policy.json rootfs /bin/echo hello` writes a valid policy
    - `run --seccomp-policy /tmp/echo-policy.json rootfs /bin/echo hello` succeeds
    - `run --seccomp-policy /tmp/echo-policy.json rootfs /bin/sh -c 'uname -a'` fails with denial context
8. **Phase 2**: Each feature tested independently

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

Four features, each independently implementable:

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

---

## Project Structure

```
denver/
├── pom.xml
├── src/main/java/org/jcontainer/
│   ├── JContainer.java              # Entry point, run/child dispatch
│   ├── ContainerParent.java          # Parent process logic
│   ├── ContainerChild.java           # Child process logic
│   └── runtime/
│       ├── ContainerRuntime.java     # Interface for platform-specific ops
│       ├── LinuxRuntime.java         # Linux: namespaces, pivot_root, mount
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
│   └── setup-rootfs-macos.sh        # Creates a minimal rootfs for macOS testing
└── rootfs/                           # (gitignored) container root filesystem
```


## Build & Run

```bash
# Build
mvn clean package

# Setup rootfs (Linux)
./scripts/setup-rootfs.sh
# Setup rootfs (macOS — requires Docker)
./scripts/setup-rootfs-macos.sh

# Run — Linux (full isolation)
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer run rootfs /bin/sh

# Run — macOS (chroot-only, limited isolation)
sudo java --enable-native-access=ALL-UNNAMED \
  -cp target/jcontainer-1.0-SNAPSHOT.jar \
  org.jcontainer.JContainer run rootfs /bin/sh

# Run tests
mvn test                    # Unit tests
mvn verify -Pintegration    # Integration tests (requires rootfs + root)
```

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

### Integration Tests (require rootfs, root privileges, platform-specific)

#### `ContainerIntegrationTest.java`
Annotated with `@Tag("integration")`, skipped by default (enabled via Maven failsafe plugin or `-Pintegration` profile).

**Platform-conditional tests** (use JUnit `@EnabledOnOs`):

- **`testContainerSeesIsolatedFilesystem`** (Linux + macOS): Run `ls /` inside container, verify output matches rootfs contents (e.g., contains Alpine's `/bin`, `/etc`) and not host root
- **`testContainerHostname`** (Linux only): Run `hostname` inside container, verify output is "container"
- **`testContainerPidNamespace`** (Linux only): Run `cat /proc/1/cmdline` inside container, verify PID 1 is the launched command (not host init)
- **`testContainerExitCode`** (Linux + macOS): Run `exit 42` and verify the parent process gets exit code 42
- **`testContainerStdout`** (Linux + macOS): Run `echo hello` and capture stdout, verify "hello" appears
- **`testMacOSWarning`** (macOS only): Capture stderr, verify isolation-limited warning is printed

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
    - `ls /` → rootfs contents, not host root
    - `exit` → clean return to host
    - Warning printed about limited isolation
6. **Integration tests**: `sudo mvn verify -Pintegration` passes on respective platforms
7. **Phase 2**: Each feature tested independently

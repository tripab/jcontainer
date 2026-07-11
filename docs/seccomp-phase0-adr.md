# Seccomp Phase 0 ADR

## Status

Accepted for implementation.

## Context

The current Linux child flow performs namespace and filesystem setup in Java and then launches the payload with `ProcessBuilder`. That leaves the Java child process alive as an intermediate boundary between container setup and the final workload. For seccomp, that is the wrong place to stop. The filter must attach to the process that actually becomes the payload, otherwise the Java process retains a broader syscall surface and the payload does not become the true post-setup boundary.

The Phase 0 goal is to lock down the implementation decisions that affect ABI work, runtime shape, and operator behavior before the profiling and filter-building work starts.

## Decisions

### 1. Correct the payload boundary with native `execv`

The Java child process will remain responsible for namespace, hostname, and filesystem setup. After setup is complete, it must transition directly into the requested payload with native `execv` rather than spawning a subprocess with `ProcessBuilder`.

This change is required for two reasons:

- the seccomp filter must be installed on the process that will become the payload
- the payload should become the real PID 1 inside the PID namespace on Linux

`ProcessBuilder` remains valid in the parent orchestration path, but not for the final child-to-payload handoff.

### 2. Keep seccomp support Linux-only in v1

Seccomp profiling and enforcement are Linux-only features in this repository.

On macOS:

- `profile` must fail with an explicit unsupported-feature error
- `run --seccomp-policy ...` must fail with an explicit unsupported-feature error

The existing degraded macOS container mode remains available for non-seccomp workflows, but seccomp flags must not silently degrade.

### 3. Resolve only absolute payload paths in v1

The payload command must use an absolute executable path such as `/bin/echo`.

After `pivot_root` on Linux, or `chroot` on macOS, executable resolution happens against the new root. In v1, the runtime will not perform `PATH` lookup. This keeps resolution deterministic and avoids ambiguities about whether lookup should happen before or after root switching.

If the command does not begin with `/`, the child should fail with a clear absolute-path-required error.

### 4. Load and validate policy JSON in the child before the final handoff

When `--seccomp-policy` is provided, policy loading and validation happen in the child path, after CLI parsing and before the final payload handoff.

The child is the right place for this because it has the authoritative runtime boundary that will install the filter and perform the final `execv`. Validation at this stage should cover:

- supported policy version
- architecture match against the current host
- known syscall names
- deterministic deduplication of syscall names

### 5. Install seccomp with `prctl`, not `seccomp()`

The v1 install path will use:

1. `prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)`
2. `prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ...)`

This is sufficient for the allowlist-based filter planned here and avoids extra complexity from seccomp flags or newer APIs that are not needed in v1.

### 6. Always inject bootstrap `execve`

The filter builder must always allow the final `execve`, even if the profiled steady-state syscall set does not include it.

The reason is structural: the child installs the filter and then immediately crosses the final `execv` boundary. Without that bootstrap allowance, the process would deny its own handoff into the payload.

### 7. Use a versioned, same-architecture JSON policy format

The v1 policy format is JSON and versioned. Policies are same-architecture only.

Required invariants:

- `version` must be supported
- `arch` must match the current Linux architecture
- syscall names must be deterministic and sorted in serialized output
- default deny action remains `ERRNO(EPERM)` for operator clarity

A policy generated on `linux-x86_64` must be rejected on `linux-aarch64`, and vice versa.

### 8. Enforce a deterministic linear allowlist ceiling before filter generation

The filter generator must reject oversized policies before attempting to install them.

For the planned linear cBPF layout, each allowlisted syscall consumes one compare instruction. Fixed overhead is architecture-dependent:

- `x86_64`: `8` fixed instructions
- `aarch64`: `6` fixed instructions

That yields these v1 ceilings against the Linux classic BPF instruction limit of `4096`:

- `x86_64`: at most `4088` allowlisted syscalls
- `aarch64`: at most `4090` allowlisted syscalls

The `x86_64` path carries two extra instructions for the required x32 ABI rejection check. If a policy exceeds the architecture-specific ceiling, generation must fail early with an explicit error describing the computed instruction count and the maximum supported allowlist size.

## Operator-visible behavior

Successful behavior:

- profiling generates a deterministic JSON policy for a Linux workload
- enforcing a compatible policy allows the payload to run normally
- the payload becomes the actual post-setup process boundary

Failure behavior:

- non-Linux seccomp entry points fail explicitly
- relative command paths fail explicitly
- invalid or mismatched policy files fail before filter installation
- denied syscalls fail with `EPERM` rather than a hard kill in v1

## Consequences

- `ContainerRuntime.execCommand(...)` needs to move away from `String[]` subprocess execution and toward resolved executable data plus optional seccomp policy
- `Syscalls` needs native bindings for `execv` and `prctl`
- integration coverage should assert that the Linux payload becomes PID 1 after the refactor
- seccomp implementation work can proceed without revisiting the basic process-boundary and platform-scope decisions

package org.jcontainer.runtime;

import org.jcontainer.ResolvedExecutable;

import java.nio.file.Path;
import java.util.List;

/**
 * Platform-specific container runtime operations.
 * Linux provides full namespace isolation; macOS provides chroot-based filesystem isolation.
 */
public interface ContainerRuntime {

    /**
     * Build the command list to spawn the child process.
     * On Linux, this wraps with {@code unshare} for mount, UTS, PID, and
     * optionally network namespaces.
     * On macOS, this is a plain Java invocation.
     *
     * @param networkEnabled if true, the child is spawned in a new network namespace (Linux only)
     */
    List<String> buildChildCommand(String javaPath, String classpath,
                                   Path seccompPolicy, String rootfs, String[] command,
                                   boolean networkEnabled);

    /**
     * Set up the parent process before spawning the child.
     * On Linux, this is intentionally a no-op because namespace creation is
     * delegated to the child launcher command.
     * On macOS, this is a no-op.
     */
    void setupParent();

    /**
     * Set up filesystem isolation in the child process.
     * On Linux, performs bind mount, pivot_root, proc mount.
     * On macOS, performs chroot.
     */
    void setupFilesystem(String rootfs);

    /**
     * Set the container hostname.
     * On Linux, calls {@code sethostname(2)} in the UTS namespace.
     * On macOS, this is skipped (would affect the host).
     */
    void setHostname(String hostname);

    /**
     * Execute the target command inside the container.
     */
    void execCommand(ResolvedExecutable executable, Path seccompPolicy);
}

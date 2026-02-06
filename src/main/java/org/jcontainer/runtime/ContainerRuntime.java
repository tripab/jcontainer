package org.jcontainer.runtime;

import java.util.List;

/**
 * Platform-specific container runtime operations.
 * Linux provides full namespace isolation; macOS provides chroot-based filesystem isolation.
 */
public interface ContainerRuntime {

    /**
     * Build the command list to spawn the child process.
     * On Linux, this wraps with {@code unshare --pid --fork} for PID namespace.
     * On macOS, this is a plain Java invocation.
     */
    List<String> buildChildCommand(String javaPath, String classpath,
                                   String rootfs, String[] command);

    /**
     * Set up the parent process before spawning the child.
     * On Linux, creates UTS and mount namespaces via {@code unshare(2)}.
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
    void execCommand(String[] command);
}

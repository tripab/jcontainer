package org.jcontainer;

import org.jcontainer.runtime.ContainerRuntime;
import org.jcontainer.runtime.LinuxRuntime;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Launches the internal child path under strace for syscall profiling.
 */
public class StraceRunner {
    private final ContainerRuntime runtime;
    private final String javaPath;
    private final String classpath;

    public StraceRunner(ContainerRuntime runtime) {
        this(runtime, ContainerParent.resolveJavaPath(), ContainerParent.resolveClasspath());
    }

    StraceRunner(ContainerRuntime runtime, String javaPath, String classpath) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.javaPath = Objects.requireNonNull(javaPath, "javaPath");
        this.classpath = Objects.requireNonNull(classpath, "classpath");
    }

    public ProfileRunResult run(ProfileConfig config, String rootfs, Path traceBase)
            throws IOException, InterruptedException {
        List<String> command = buildCommand(config, rootfs, traceBase);
        Process process = startProcess(command);
        int exitCode = process.waitFor();
        return new ProfileRunResult(traceBase, exitCode);
    }

    List<String> buildCommand(ProfileConfig config, String rootfs, Path traceBase) {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(rootfs, "rootfs");
        Objects.requireNonNull(traceBase, "traceBase");

        List<String> childCommand = runtime.buildChildCommand(
                javaPath, classpath, null, rootfs, config.command(), false);
        return injectStrace(childCommand, traceBase);
    }

    protected Process startProcess(List<String> command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectInput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        return processBuilder.start();
    }

    private List<String> injectStrace(List<String> childCommand, Path traceBase) {
        List<String> wrappedCommand = new ArrayList<>(childCommand);
        int insertIndex = straceInsertIndex(wrappedCommand);
        wrappedCommand.add(insertIndex++, "strace");
        wrappedCommand.add(insertIndex++, "-ff");
        wrappedCommand.add(insertIndex++, "-o");
        wrappedCommand.add(insertIndex, traceBase.toString());
        return wrappedCommand;
    }

    private int straceInsertIndex(List<String> childCommand) {
        if (runtime instanceof LinuxRuntime) {
            int forkIndex = childCommand.indexOf("--fork");
            if (forkIndex < 0) {
                throw new IllegalStateException("Linux child command is missing --fork: " + childCommand);
            }
            return forkIndex + 1;
        }
        return 0;
    }
}

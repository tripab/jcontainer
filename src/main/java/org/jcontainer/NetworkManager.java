package org.jcontainer;

import java.io.IOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * Manages network namespace setup for a container using a veth pair.
 * Creates a virtual ethernet pair: one end on the host, one inside the container's
 * network namespace. Uses the {@code ip} command for all operations.
 *
 * <p>IP scheme: host gets 10.0.0.1/24, container gets 10.0.0.2/24.</p>
 */
public class NetworkManager implements AutoCloseable {

    static final String HOST_IP = "10.0.0.1";
    static final String CONTAINER_IP = "10.0.0.2";
    static final String SUBNET = "/24";
    static final String CONTAINER_DEV = "eth0";

    private final String containerId;
    private final String hostDev;
    private boolean created;

    public NetworkManager() {
        this.containerId = generateId();
        this.hostDev = "veth-" + containerId;
        this.created = false;
    }

    /**
     * Create with a specific container ID (for testing).
     */
    NetworkManager(String containerId) {
        this.containerId = containerId;
        this.hostDev = "veth-" + containerId;
        this.created = false;
    }

    /**
     * Set up the veth pair and configure networking.
     *
     * @param childPid PID of the child process in the new network namespace
     */
    public void setup(long childPid) throws IOException {
        List<String[]> commands = buildSetupCommands(childPid);
        for (String[] cmd : commands) {
            exec(cmd);
        }
        created = true;
    }

    /**
     * Build the sequence of commands to set up networking.
     * Exposed for testing.
     */
    List<String[]> buildSetupCommands(long childPid) {
        String pid = Long.toString(childPid);
        String nsNet = "/proc/" + pid + "/ns/net";

        return List.of(
                // Create veth pair
                new String[]{"ip", "link", "add", hostDev, "type", "veth", "peer", "name", CONTAINER_DEV},
                // Move container end into the child's network namespace
                new String[]{"ip", "link", "set", CONTAINER_DEV, "netns", pid},
                // Configure host side
                new String[]{"ip", "addr", "add", HOST_IP + SUBNET, "dev", hostDev},
                new String[]{"ip", "link", "set", hostDev, "up"},
                // Configure container side (via nsenter)
                new String[]{"nsenter", "--net=" + nsNet, "ip", "addr", "add", CONTAINER_IP + SUBNET, "dev", CONTAINER_DEV},
                new String[]{"nsenter", "--net=" + nsNet, "ip", "link", "set", CONTAINER_DEV, "up"},
                new String[]{"nsenter", "--net=" + nsNet, "ip", "link", "set", "lo", "up"},
                new String[]{"nsenter", "--net=" + nsNet, "ip", "route", "add", "default", "via", HOST_IP}
        );
    }

    /**
     * Build the command to tear down the veth pair.
     * Deleting the host end automatically removes the container end.
     */
    String[] buildCleanupCommand() {
        return new String[]{"ip", "link", "delete", hostDev};
    }

    @Override
    public void close() {
        if (!created) {
            return;
        }
        try {
            exec(buildCleanupCommand());
        } catch (IOException e) {
            System.err.println("WARNING: Failed to clean up veth pair " + hostDev + ": " + e.getMessage());
        }
    }

    public String getContainerId() {
        return containerId;
    }

    public String getHostDev() {
        return hostDev;
    }

    private void exec(String[] cmd) throws IOException {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.inheritIO();
            int rc = pb.start().waitFor();
            if (rc != 0) {
                throw new IOException("Command failed (rc=" + rc + "): " + String.join(" ", cmd));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Command interrupted: " + String.join(" ", cmd), e);
        }
    }

    private static String generateId() {
        byte[] bytes = new byte[4];
        new Random().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}

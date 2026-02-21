package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NetworkManagerTest {

    @Test
    void testHostDevNameContainsId() {
        NetworkManager mgr = new NetworkManager("abc123");
        assertEquals("veth-abc123", mgr.getHostDev());
    }

    @Test
    void testContainerId() {
        NetworkManager mgr = new NetworkManager("test01");
        assertEquals("test01", mgr.getContainerId());
    }

    @Test
    void testBuildSetupCommandsCount() {
        NetworkManager mgr = new NetworkManager("test01");
        List<String[]> commands = mgr.buildSetupCommands(12345);
        assertEquals(8, commands.size());
    }

    @Test
    void testCreateVethPairCommand() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(0);
        assertArrayEquals(
                new String[]{"ip", "link", "add", "veth-test01", "type", "veth", "peer", "name", "eth0"},
                cmd);
    }

    @Test
    void testMoveToNamespaceCommand() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(1);
        assertArrayEquals(
                new String[]{"ip", "link", "set", "eth0", "netns", "12345"},
                cmd);
    }

    @Test
    void testHostIpAssignment() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(2);
        assertArrayEquals(
                new String[]{"ip", "addr", "add", "10.0.0.1/24", "dev", "veth-test01"},
                cmd);
    }

    @Test
    void testHostDevUp() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(3);
        assertArrayEquals(
                new String[]{"ip", "link", "set", "veth-test01", "up"},
                cmd);
    }

    @Test
    void testContainerIpAssignment() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(4);
        assertArrayEquals(
                new String[]{"nsenter", "--net=/proc/12345/ns/net", "ip", "addr", "add", "10.0.0.2/24", "dev", "eth0"},
                cmd);
    }

    @Test
    void testContainerDevUp() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(5);
        assertArrayEquals(
                new String[]{"nsenter", "--net=/proc/12345/ns/net", "ip", "link", "set", "eth0", "up"},
                cmd);
    }

    @Test
    void testLoopbackUp() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(6);
        assertArrayEquals(
                new String[]{"nsenter", "--net=/proc/12345/ns/net", "ip", "link", "set", "lo", "up"},
                cmd);
    }

    @Test
    void testDefaultRoute() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildSetupCommands(12345).get(7);
        assertArrayEquals(
                new String[]{"nsenter", "--net=/proc/12345/ns/net", "ip", "route", "add", "default", "via", "10.0.0.1"},
                cmd);
    }

    @Test
    void testCleanupCommand() {
        NetworkManager mgr = new NetworkManager("test01");
        String[] cmd = mgr.buildCleanupCommand();
        assertArrayEquals(new String[]{"ip", "link", "delete", "veth-test01"}, cmd);
    }

    @Test
    void testIpConstants() {
        assertEquals("10.0.0.1", NetworkManager.HOST_IP);
        assertEquals("10.0.0.2", NetworkManager.CONTAINER_IP);
        assertEquals("/24", NetworkManager.SUBNET);
        assertEquals("eth0", NetworkManager.CONTAINER_DEV);
    }
}

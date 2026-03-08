package dev.jumpwatch.serverfabric.host.instance;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class PortManager {

    private final int portMin;
    private final int portMax;
    private final Set<Integer> reservedPorts = Collections.synchronizedSet(new HashSet<>());

    public PortManager(int portMin, int portMax) {
        this.portMin = portMin;
        this.portMax = portMax;
    }

    public void loadExisting(Collection<Path> instanceDirs, InstanceStore store) {
        for (Path p : instanceDirs) {
            try {
                InstanceMeta meta = store.readMeta(p);
                if (meta != null && meta.port > 0) {
                    reservedPorts.add(meta.port);
                }
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] PortManager loadExisting failed for " + p.getFileName() + ": " + e.getMessage());
            }
        }
    }

    public int allocate() throws IOException {
        for (int p = portMin; p <= portMax; p++) {
            if (reservedPorts.contains(p)) continue;
            if (isPortFree(p)) {
                reservedPorts.add(p);
                return p;
            }
        }
        throw new IOException("No free ports in range " + portMin + "-" + portMax);
    }

    public void release(int port) {
        if (port > 0) {
            reservedPorts.remove(port);
        }
    }

    public boolean isReserved(int port) {
        return reservedPorts.contains(port);
    }

    private static boolean isPortFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }
}

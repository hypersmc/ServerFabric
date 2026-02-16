package dev.jumpwatch.serverfabric.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ManagedInstance {

    public enum State { STARTING, RUNNING, CRASHED, STOPPED }

    public interface StateListener {
        void onState(String instanceName, State newState);
    }

    public interface ExitListener {
        void onExit(String instanceName, int exitCode, boolean stopping);
    }

    public enum ReadinessType { LOG_CONTAINS, TCP_PORT, NONE }

    private final String javaCmd;
    private final List<String> jvmArgs;
    private final String name;
    private final Path dir;
    private final Path jar;

    private final StateListener stateListener;
    private final ExitListener exitListener;

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    private final ReadinessType readinessType;
    private final String readinessLogContains;
    private final String readinessHost;
    private final int readinessPort;
    private final long readinessTimeoutMs;

    private Process process;
    private BufferedWriter stdin;

    public ManagedInstance(
            String javaCmd,
            List<String> jvmArgs,
            String name,
            Path dir,
            Path jar,
            // readiness
            ReadinessType readinessType,
            String readinessLogContains,
            String readinessHost,
            int readinessPort,
            long readinessTimeoutMs,
            // callbacks
            StateListener stateListener,
            ExitListener exitListener
    ) {
        this.javaCmd = javaCmd;
        this.jvmArgs = jvmArgs;
        this.name = name;
        this.dir = dir;
        this.jar = jar;

        this.readinessType = readinessType == null ? ReadinessType.LOG_CONTAINS : readinessType;
        this.readinessLogContains = (readinessLogContains == null || readinessLogContains.isBlank())
                ? "Done ("
                : readinessLogContains;
        this.readinessHost = (readinessHost == null || readinessHost.isBlank()) ? "127.0.0.1" : readinessHost;
        this.readinessPort = readinessPort;
        this.readinessTimeoutMs = readinessTimeoutMs <= 0 ? 20_000 : readinessTimeoutMs;

        this.stateListener = stateListener;
        this.exitListener = exitListener;
    }

    public void start() throws IOException {
        if (isAlive()) throw new IOException("Process already running");

        stopping.set(false);
        setState(State.STARTING);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        cmd.addAll(jvmArgs);
        cmd.add("-jar");
        cmd.add(jar.getFileName().toString());
        cmd.add("nogui");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(dir.toFile());
        pb.redirectErrorStream(true);

        process = pb.start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        startStdoutPump(process.getInputStream());
        startExitWatcher();
        startReadinessWatcher();
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void stopGraceful() throws IOException {
        if (!isAlive()) return;
        stopping.set(true);

        // Do NOT set STOPPED immediately; wait for real process exit.
        // Otherwise we lose crash classification.
        sendCommand("stop");
    }

    public void sendCommand(String cmd) throws IOException {
        if (!isAlive()) throw new IOException("Instance not running");
        if (stdin == null) throw new IOException("stdin not ready");

        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;

        // Avoid deadlocks if the process died mid-write
        try {
            stdin.write(clean + "\n");
            stdin.flush();
        } catch (IOException e) {
            throw new IOException("Failed to write to instance stdin (process may be dead): " + e.getMessage(), e);
        }
    }

    public State getState() {
        if (!isAlive()) {
            State s = state.get();
            if (s == State.RUNNING || s == State.STARTING) return State.CRASHED;
        }
        return state.get();
    }

    private void setState(State s) {
        state.set(s);
        if (stateListener != null) stateListener.onState(name, s);
    }

    private void startStdoutPump(InputStream in) {
        Thread t = new Thread(() -> {
            // Always drain stdout as bytes to avoid pipe deadlocks.
            byte[] buf = new byte[8192];
            int n;

            // For log-based readiness, we do a *light* line scan without blocking drain.
            StringBuilder lineBuf = new StringBuilder(512);

            try {
                while ((n = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    System.out.print("[" + name + "] " + chunk);

                    if (readinessType == ReadinessType.LOG_CONTAINS && state.get() == State.STARTING) {
                        // Scan chunk for readiness string, but also handle it across boundaries.
                        // Accumulate and split on newline when present; never block.
                        lineBuf.append(chunk);

                        // prevent unbounded growth if no newlines exist
                        if (lineBuf.length() > 32_000) {
                            // keep last part
                            lineBuf.delete(0, lineBuf.length() - 4_000);
                        }

                        if (lineBuf.indexOf(readinessLogContains) >= 0) {
                            setState(State.RUNNING);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }, "ServerFabric-Host-stdout-" + name);

        t.setDaemon(true);
        t.start();
    }

    private void startExitWatcher() {
        Thread t = new Thread(() -> {
            int code = -1;
            try {
                code = process.waitFor();
            } catch (InterruptedException ignored) {
            }

            // Close stdin if still open
            try { if (stdin != null) stdin.close(); } catch (Exception ignored) {}

            boolean wasStopping = stopping.get();

            // Classify end-state based on intent, not exit code
            if (wasStopping) {
                setState(State.STOPPED);
            } else {
                setState(State.CRASHED);
            }

            System.out.println("[" + name + "] exited with code " + code + " stopping=" + wasStopping);

            if (exitListener != null) {
                try { exitListener.onExit(name, code, wasStopping); } catch (Exception ignored) {}
            }
        }, "ServerFabric-Host-exit-" + name);

        t.setDaemon(true);
        t.start();
    }

    private void startReadinessWatcher() {
        if (readinessType == ReadinessType.NONE) return;
        if (readinessType == ReadinessType.LOG_CONTAINS) return; // handled by stdout pump

        // TCP_PORT readiness: mark RUNNING once the port is reachable
        if (readinessType == ReadinessType.TCP_PORT) {
            Thread t = new Thread(() -> {
                long deadline = System.currentTimeMillis() + readinessTimeoutMs;
                while (System.currentTimeMillis() < deadline) {
                    if (!isAlive()) return;
                    if (state.get() != State.STARTING) return;

                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(readinessHost, readinessPort), 750);
                        setState(State.RUNNING);
                        return;
                    } catch (IOException ignored) {
                    }

                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }
                // timeout: leave as STARTING; your manager can decide what to do
            }, "ServerFabric-Host-ready-" + name);

            t.setDaemon(true);
            t.start();
        }
    }
}
package dev.jumpwatch.serverfabric.host.instance;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class ManagedInstance {

    public enum State {
        STARTING,
        RUNNING,
        STOPPING,
        START_TIMEOUT,
        CRASHED,
        STOPPED
    }

    public interface StateListener {
        void onState(String instanceName, State newState);
    }

    public interface ExitListener {
        void onExit(String instanceName, int exitCode, boolean stopping);
    }

    public enum ReadinessType {
        LOG_CONTAINS,
        TCP_PORT,
        NONE
    }

    public record RuntimeSnapshot(
            String name,
            State state,
            boolean alive,
            boolean stopping,
            long startedAtMs,
            long lastOutputAtMs,
            long pid
    ) {}

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

    private final InstanceLogBuffer logBuffer = new InstanceLogBuffer(200);

    private final AtomicLong startedAtMs = new AtomicLong(0L);
    private final AtomicLong lastOutputAtMs = new AtomicLong(0L);

    private volatile Process process;
    private volatile BufferedWriter stdin;

    public ManagedInstance(
            String javaCmd,
            List<String> jvmArgs,
            String name,
            Path dir,
            Path jar,
            ReadinessType readinessType,
            String readinessLogContains,
            String readinessHost,
            int readinessPort,
            long readinessTimeoutMs,
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
        this.readinessTimeoutMs = readinessTimeoutMs <= 0 ? 100_000 : readinessTimeoutMs;

        this.stateListener = stateListener;
        this.exitListener = exitListener;
    }

    public void start() throws IOException {
        if (isAlive()) {
            throw new IOException("Process already running");
        }

        stopping.set(false);
        startedAtMs.set(System.currentTimeMillis());
        lastOutputAtMs.set(System.currentTimeMillis());
        logBuffer.clear();

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
        Process p = process;
        return p != null && p.isAlive();
    }

    public void stopGraceful() throws IOException {
        if (!isAlive()) {
            return;
        }

        State current = state.get();
        if (current == State.STOPPING || current == State.STOPPED) {
            return;
        }

        stopping.set(true);
        setState(State.STOPPING);
        sendCommand("stop");
    }

    public void stopForcefully() {
        Process p = process;
        if (p == null || !p.isAlive()) {
            return;
        }

        stopping.set(true);
        setState(State.STOPPING);

        try {
            p.destroyForcibly();
        } catch (Exception e) {
            System.out.println("[" + name + "] force kill failed: " + e.getMessage());
        }
    }

    public void sendCommand(String cmd) throws IOException {
        if (!isAlive()) {
            throw new IOException("Instance not running");
        }
        if (stdin == null) {
            throw new IOException("stdin not ready");
        }

        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;

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

            if (s == State.STOPPING) {
                return State.STOPPED;
            }
            if (s == State.RUNNING || s == State.STARTING || s == State.START_TIMEOUT) {
                return stopping.get() ? State.STOPPED : State.CRASHED;
            }
        }

        return state.get();
    }

    public RuntimeSnapshot snapshot() {
        return new RuntimeSnapshot(
                name,
                getState(),
                isAlive(),
                stopping.get(),
                startedAtMs.get(),
                lastOutputAtMs.get(),
                getPid().orElse(-1L)
        );
    }

    public OptionalLong getPid() {
        Process p = process;
        if (p == null) return OptionalLong.empty();

        try {
            return OptionalLong.of(p.pid());
        } catch (Exception e) {
            return OptionalLong.empty();
        }
    }

    public long getStartedAtMs() {
        return startedAtMs.get();
    }

    public long getLastOutputAtMs() {
        return lastOutputAtMs.get();
    }

    public List<String> getRecentLogLines() {
        return logBuffer.snapshot();
    }

    private void setState(State newState) {
        State oldState = state.getAndSet(newState);
        if (oldState != newState && stateListener != null) {
            stateListener.onState(name, newState);
        }
    }

    private void startStdoutPump(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            int n;

            StringBuilder readinessBuffer = new StringBuilder(1024);

            try {
                while ((n = in.read(buf)) != -1) {
                    String chunk = new String(buf, 0, n, StandardCharsets.UTF_8);
                    lastOutputAtMs.set(System.currentTimeMillis());

                    System.out.print("[" + name + "] " + chunk);

                    logBuffer.appendChunk(chunk);

                    if (readinessType == ReadinessType.LOG_CONTAINS && state.get() == State.STARTING) {
                        readinessBuffer.append(chunk);

                        if (readinessBuffer.indexOf(readinessLogContains) >= 0) {
                            setState(State.RUNNING);
                        }

                        if (readinessBuffer.length() > 32_000) {
                            readinessBuffer.delete(0, readinessBuffer.length() - 4_000);
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
                Process p = process;
                if (p != null) {
                    code = p.waitFor();
                }
            } catch (InterruptedException ignored) {
            }

            try {
                if (stdin != null) {
                    stdin.close();
                }
            } catch (Exception ignored) {
            }

            boolean wasStopping = stopping.get();

            if (wasStopping) {
                setState(State.STOPPED);
            } else {
                setState(State.CRASHED);
            }

            System.out.println("[" + name + "] exited with code " + code + " stopping=" + wasStopping);

            if (exitListener != null) {
                try {
                    exitListener.onExit(name, code, wasStopping);
                } catch (Exception ignored) {
                }
            }
        }, "ServerFabric-Host-exit-" + name);

        t.setDaemon(true);
        t.start();
    }

    private void startReadinessWatcher() {
        if (readinessType == ReadinessType.NONE) {
            return;
        }

        if (readinessType == ReadinessType.LOG_CONTAINS) {
            startReadinessTimeoutWatcher();
            return;
        }

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

                    try {
                        Thread.sleep(250);
                    } catch (InterruptedException ignored) {
                    }
                }

                if (isAlive() && state.get() == State.STARTING) {
                    System.out.println("[" + name + "] readiness timed out after " + readinessTimeoutMs + "ms");
                    setState(State.START_TIMEOUT);
                }
            }, "ServerFabric-Host-ready-" + name);

            t.setDaemon(true);
            t.start();
        }
    }

    private void startReadinessTimeoutWatcher() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(readinessTimeoutMs);
            } catch (InterruptedException ignored) {
            }

            if (isAlive() && state.get() == State.STARTING) {
                System.out.println("[" + name + "] readiness timed out after " + readinessTimeoutMs + "ms");
                setState(State.START_TIMEOUT);
            }
        }, "ServerFabric-Host-ready-timeout-" + name);

        t.setDaemon(true);
        t.start();
    }
}
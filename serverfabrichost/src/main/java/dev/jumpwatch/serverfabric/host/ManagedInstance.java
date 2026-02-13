package dev.jumpwatch.serverfabric.host;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class ManagedInstance {

    public enum State { STARTING, RUNNING, CRASHED, STOPPED }

    public interface StateListener {
        void onState(String instanceName, State newState);
    }

    private final String javaCmd;
    private final List<String> jvmArgs;
    private final String name;
    private final Path dir;
    private final Path jar;
    private final StateListener listener;

    private final AtomicReference<State> state = new AtomicReference<>(State.STOPPED);

    private Process process;
    private BufferedWriter stdin;

    public ManagedInstance(String javaCmd, List<String> jvmArgs, String name, Path dir, Path jar, StateListener listener) {
        this.javaCmd = javaCmd;
        this.jvmArgs = jvmArgs;
        this.name = name;
        this.dir = dir;
        this.jar = jar;
        this.listener = listener;
    }

    public void start() throws IOException {
        if (isAlive()) throw new IOException("Process already running");

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

        Thread logThread = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    System.out.println("[" + name + "] " + line);

                    // readiness signal
                    if (state.get() == State.STARTING && line.contains("Done (")) {
                        setState(State.RUNNING);
                    }
                }
            } catch (IOException ignored) {}
        }, "ServerFabric-Host-log-" + name);
        logThread.setDaemon(true);
        logThread.start();

        Thread exitThread = new Thread(() -> {
            try {
                int code = process.waitFor();
                // If we didn't intentionally stop, treat as crash
                State s = state.get();
                if (s == State.RUNNING || s == State.STARTING) {
                    setState(State.CRASHED);
                }
                System.out.println("[" + name + "] exited with code " + code);
            } catch (InterruptedException ignored) {}
        }, "ServerFabric-Host-exit-" + name);
        exitThread.setDaemon(true);
        exitThread.start();
    }

    public boolean isAlive() {
        return process != null && process.isAlive();
    }

    public void stopGraceful() throws IOException {
        if (!isAlive()) return;
        setState(State.STOPPED);
        stdin.write("stop\n");
        stdin.flush();
    }

    public void sendCommand(String cmd) throws IOException {
        if (!isAlive()) throw new IOException("Instance not running");
        String clean = cmd.startsWith("/") ? cmd.substring(1) : cmd;
        stdin.write(clean + "\n");
        stdin.flush();
    }

    public State getState() {
        if (!isAlive() && state.get() == State.RUNNING) return State.CRASHED;
        return state.get();
    }

    private void setState(State s) {
        state.set(s);
        if (listener != null) listener.onState(name, s);
    }
}
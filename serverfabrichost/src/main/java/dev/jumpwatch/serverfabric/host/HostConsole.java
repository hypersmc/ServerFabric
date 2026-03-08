package dev.jumpwatch.serverfabric.host;

import dev.jumpwatch.serverfabric.host.instance.InstanceManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

public final class HostConsole implements Runnable {

    private final InstanceManager mgr;
    private final Runnable requestStopHost;
    private volatile String selected = null;

    public HostConsole(InstanceManager mgr, Runnable requestStopHost) {
        this.mgr = mgr;
        this.requestStopHost = requestStopHost;
    }

    @Override
    public void run() {
        System.out.println("[ServerFabric-Host] Console ready. Commands: help, list, select <name>, endselection, stop, kill, killinstance <name>");

        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                // When selected, forward most input to the instance
                if (selected != null) {
                    String lower = line.toLowerCase(Locale.ROOT);
                    if (lower.equals("endselection") || lower.equals("end") || lower.equals("unselect")) {
                        System.out.println("[ServerFabric-Host] Selection cleared (was " + selected + ")");
                        selected = null;
                        continue;
                    }

                    // Allow host commands even while selected (prefix with :)
                    if (line.startsWith(":")) {
                        handleHostCommand(line.substring(1).trim());
                        continue;
                    }

                    // Forward to instance
                    try {
                        mgr.command(selected, line);
                    } catch (Exception e) {
                        System.out.println("[ServerFabric-Host] Failed to send to " + selected + ": " + e.getMessage());
                    }
                    continue;
                }

                // Not selected -> treat as host command
                handleHostCommand(line);
            }
        } catch (Exception e) {
            System.out.println("[ServerFabric-Host] Console loop ended: " + e.getMessage());
        }
    }

    private void handleHostCommand(String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        String arg = parts.length > 1 ? parts[1].trim() : "";

        switch (cmd) {
            case "help" -> printHelp();

            case "list", "status" -> {
                try {
                    var st = mgr.status();
                    System.out.println("[ServerFabric-Host] Instances:");
                    for (var i : st.instances()) {
                        System.out.println(" - " + i.name() + " : " + i.state() + " : " + i.port());
                    }
                } catch (Exception e) {
                    System.out.println("[ServerFabric-Host] Status failed: " + e.getMessage());
                }
            }

            case "select" -> {
                if (arg.isEmpty()) {
                    System.out.println("[ServerFabric-Host] Usage: select <instance-name>");
                    return;
                }
                selected = arg;
                System.out.println("[ServerFabric-Host] Selected " + selected + ". Type commands to send. Use endselection to exit selection. Use :help for host commands.");
            }

            case "killinstance" -> {
                if (arg.isEmpty()) {
                    System.out.println("[ServerFabric-Host] Usage: killinstance <instance-name>");
                    return;
                }
                try {
                    mgr.kill(arg);
                    System.out.println("[ServerFabric-Host] Force killed " + arg);
                } catch (Exception e) {
                    System.out.println("[ServerFabric-Host] Kill failed for " + arg + ": " + e.getMessage());
                }
            }

            case "stop", "exit", "quit" -> {
                System.out.println("[ServerFabric-Host] Stop requested...");
                requestStopHost.run();
            }

            case "kill" -> {
                System.out.println("[ServerFabric-Host] Kill requested. Exiting immediately.");
                System.exit(0);
            }

            default -> System.out.println("[ServerFabric-Host] Unknown command. Type help.");
        }
    }

    private void printHelp() {
        System.out.println("""
[ServerFabric-Host] Commands:
  help                  - show this help
  list | status          - list known instances and states
  select <name>          - route console input to a specific instance
  endselection | end     - leave instance selection mode
  stop | exit | quit     - stop the host (graceful)
  killinstance <name>    - Kill a server instance (forcefully)
  kill                  - exit immediately

While selected:
  <anything>             - forwarded to the instance console (no leading / required)
  :<hostcmd>             - run a host command (example: :list)
""");
    }
}

package dev.jumpwatch.serverfabric.host;

import com.sun.net.httpserver.HttpServer;
import dev.jumpwatch.serverfabric.host.instance.InstanceManager;
import dev.jumpwatch.serverfabric.host.schedule.SchedulerService;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

//ServerFabric-Host
public final class ServerFabricHostMain {
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("[ServerFabric-Host] Uncaught in " + t.getName() + ": " + e);
            e.printStackTrace();
        });

        Path configPath = Path.of("dyn", "config.properties");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase("--config")) {
                configPath = Path.of(args[i + 1]);
                break;
            }
        }

        ensureDefaultConfigExists(configPath);

        HostConfig cfg = HostConfig.load(configPath);
        new HostBootstrap(cfg).run();
        InstanceManager mgr = new InstanceManager(cfg);

        HttpServer server = HttpServer.create(new InetSocketAddress(cfg.bindHost(), cfg.bindPort()), 0);
        server.setExecutor(new ThreadPoolExecutor(
                4, 32,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500)
        ));

        HostHttpApi api = new HostHttpApi(cfg.token(), mgr);
        api.register(server);
        server.start();

        SchedulerService scheduler = new SchedulerService(mgr, cfg.rootPath());
        scheduler.startIfPresent();

        System.out.println("ServerFabric-Host listening on " + cfg.bindHost() + ":" + cfg.bindPort());
        System.out.println("Root: " + cfg.rootPath());

        AtomicBoolean stopping = new AtomicBoolean(false);

        Runnable requestStopHost = () -> {
            if (!stopping.compareAndSet(false, true)) return;

            try {
                System.out.println("[ServerFabric-Host] Persisting instance states...");
                mgr.persistAllLiveStates();

                System.out.println("[ServerFabric-Host] Gracefully stopping running instances before stopping.");
                mgr.stopAllGraceful();

                System.out.println("[ServerFabric-Host] Stopping HTTP server...");
                server.stop(0);
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Stop failed: " + e.getMessage());
            } finally {
                System.out.println("[ServerFabric-Host] Exiting.");
                System.exit(0);
            }
        };

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ServerFabric-Host] Shutdown hook triggered, persisting instance states...");
            try {
                mgr.persistAllLiveStates();
                scheduler.shutdown();
            } catch (Exception ignored) {}
        }, "ServerFabric-Host-shutdown"));

        // Start console thread
        Thread console = new Thread(new HostConsole(mgr, requestStopHost), "ServerFabric-Host-console");
        console.setDaemon(true);
        console.start();
    }



    private static void ensureDefaultConfigExists(Path configPath) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        if (Files.exists(configPath)) {
            return;
        }

        List<String> lines = List.of(
                "# ServerFabric Host default config",
                "token=CHANGE_ME_TOKEN",
                "hostId=local",
                "bindHost=0.0.0.0",
                "bindPort=8085",
                "rootPath=dyn/root",
                "javaCmd=java",
                "portMin=25566",
                "portMax=25666",
                "jvmArgs=-Xms1G -Xmx2G"
        );

        Files.write(configPath, lines, StandardOpenOption.CREATE_NEW);
        System.out.println("[ServerFabric-Host] Created default config at " + configPath.toAbsolutePath());
    }
}
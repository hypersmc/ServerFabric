package dev.jumpwatch.serverfabric.host;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

//ServerFabric-Host
public final class DynHostMain {
    public static void main(String[] args) throws Exception {
        Path configPath = Path.of("dyn", "config.properties");
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equalsIgnoreCase("--config")) {
                configPath = Path.of(args[i + 1]);
                break;
            }
        }

        HostConfig cfg = HostConfig.load(configPath);

        InstanceManager mgr = new InstanceManager(cfg);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[ServerFabric-Host] Shutdown hook triggered, persisting instance states...");
            try {
                mgr.persistAllLiveStates();
            } catch (Exception e) {
                System.out.println("[ServerFabric-Host] Shutdown hook persist failed: " + e.getMessage());
            }
        }, "ServerFabric-Host-shutdown"));

        HostHttpApi api = new HostHttpApi(cfg.token(), mgr);

        HttpServer server = HttpServer.create(
                new InetSocketAddress(cfg.bindHost(), cfg.bindPort()), 0
        );
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.out.println("[ServerFabric-Host] Uncaught in " + t.getName() + ": " + e);
            e.printStackTrace();
        });
        server.setExecutor(new ThreadPoolExecutor(
                4, 32,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500)
        ));
        api.register(server);
        server.start();

        System.out.println("ServerFabric-Host listening on " + cfg.bindHost() + ":" + cfg.bindPort());
        System.out.println("Root: " + cfg.rootPath());
    }
}
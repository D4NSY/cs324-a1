package distrilab.bootstrap;

import distrilab.net.RmiSupport;
import distrilab.util.Concurrency;
import distrilab.util.Config;
import distrilab.util.ConfigException;
import distrilab.util.Log;
import distrilab.util.NamedThreadFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Bootstrap Node process: creates an RMI registry, binds the membership service in
 * it and expires stale leases. It never takes part in elections or job processing.
 */
public final class BootstrapNode {

    private final Config config;
    private final ScheduledExecutorService sweeper =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("lease-sweeper", true));
    private final ExecutorService verifier = Executors.newCachedThreadPool(new NamedThreadFactory("verify", true));
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private BootstrapServiceImpl service;
    private Registry registry;

    public BootstrapNode(Config config) {
        this.config = config;
    }

    public static void main(String[] args) {
        Log.setNode("Bootstrap");
        try {
            Config config = Config.load(args, Config.Role.BOOTSTRAP);
            Log.setDebug(config.getBoolean("log.debug"));
            BootstrapNode node = new BootstrapNode(config);
            node.start();
            node.runConsole();
        } catch (ConfigException e) {
            Log.error("Configuration error: %s", e.getMessage());
            System.exit(2);
        } catch (ExportException e) {
            Log.error("Could not open the RMI registry port - is another bootstrap node (or rmiregistry) "
                    + "already running on it? Use --port=<other port>. (%s)", Log.describe(e));
            System.exit(1);
        } catch (RemoteException e) {
            Log.error(e, "Could not start the bootstrap node");
            System.exit(1);
        }
    }

    public void start() throws RemoteException {
        RmiSupport.configure(config);
        int port = config.getPositiveInt("bootstrap.port");
        String name = config.get("bootstrap.serviceName");
        service = new BootstrapServiceImpl(config.getPositiveInt("bootstrap.leaseTimeoutMs"), verifier);

        registry = LocateRegistry.createRegistry(port);
        registry.rebind(name, RmiSupport.export(service, 0, config));

        long sweep = config.getPositiveInt("bootstrap.sweepIntervalMs");
        sweeper.scheduleWithFixedDelay(Concurrency.guarded("lease-sweep", service::expireLeases),
                sweep, sweep, TimeUnit.MILLISECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "shutdown"));
        Log.info("Bootstrap node ready: service '%s' bound in the RMI registry on port %d (settings: %s)",
                name, port, config.getSource());
        Log.info("Workers should use --bootstrap=%s:%d. Type 'help' for console commands.",
                System.getProperty("java.rmi.server.hostname", "<this-host>"), port);
    }

    /** Reads console commands; if there is no console (background run) just waits. */
    private void runConsole() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim().toLowerCase();
                if (cmd.isEmpty()) {
                    continue;
                }
                switch (cmd) {
                    case "list":
                    case "workers":
                        System.out.println(service.describe());
                        break;
                    case "quit":
                    case "exit":
                        stop();
                        System.exit(0);
                        return;
                    case "help":
                    default:
                        System.out.println("Commands: list (active workers) | quit");
                }
            }
        } catch (IOException e) {
            Log.debug("Console closed: %s", e.getMessage());
        }
        awaitStop();
    }

    private void awaitStop() {
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        if (!stopping.compareAndSet(false, true)) {
            return; // already stopped (console 'quit' and the shutdown hook can both call this)
        }
        sweeper.shutdownNow();
        verifier.shutdownNow();
        if (service != null) {
            RmiSupport.unexportQuietly(service);
        }
        if (registry != null) {
            RmiSupport.unexportQuietly(registry);
        }
        Log.info("Bootstrap node stopped");
        stopped.countDown();
    }
}

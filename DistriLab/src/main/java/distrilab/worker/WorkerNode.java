package distrilab.worker;

import distrilab.api.RegistrationException;
import distrilab.api.WorkerService;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.WorkerRef;
import distrilab.api.model.WorkerStatus;
import distrilab.jobs.JobLimits;
import distrilab.net.BootstrapClient;
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
import java.util.List;
import java.rmi.RemoteException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A worker process. This class is the composition root: it creates the worker's
 * components, wires them together, exports the remote object, joins the overlay and
 * starts the background monitors.
 *
 * <pre>
 *   WorkerServiceImpl (RMI facade)
 *     |- OverlayManager   : join / repair / leave the unstructured network
 *     |- ElectionManager  : ELECTION wave + COORDINATOR flood, current coordinator view
 *     |- JobCoordinator   : term + JAC bookkeeping, split / dispatch / combine jobs
 *     |- TaskExecutor     : thread pool that computes parts of jobs
 *   HeartbeatMonitor      : failure detection, lease renewal
 * </pre>
 */
public final class WorkerNode {

    private final Config config;
    private final BootstrapClient bootstrap;
    private final NodeIdentity self;

    // Thread pools: each has a single, clear purpose.
    private final ExecutorService messagePool;    // election/coordinator forwarding (cached: never starves)
    private final ExecutorService dispatchPool;   // coordinator -> worker sub-task calls (cached)
    private final ExecutorService repairExecutor; // overlay repairs, one at a time
    private final ExecutorService background;     // fire-and-forget notifications

    private final NeighbourTable neighbours;
    private final TaskExecutor executor;
    private final OverlayManager overlay;
    private final JobCoordinator coordinator;
    private final ElectionManager election;
    private final WorkerServiceImpl service;
    private final HeartbeatMonitor heartbeat;

    private final AtomicBoolean stopped = new AtomicBoolean();
    private final CountDownLatch stopLatch = new CountDownLatch(1);

    private WorkerNode(Config config, BootstrapClient bootstrap, int id) {
        this.config = config;
        this.bootstrap = bootstrap;
        this.self = new NodeIdentity(id, config.get("rmi.hostname"));

        this.messagePool = Executors.newCachedThreadPool(new NamedThreadFactory("election-W" + id, true));
        this.dispatchPool = Executors.newCachedThreadPool(new NamedThreadFactory("dispatch-W" + id, true));
        this.repairExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("overlay-W" + id, true));
        this.background = Executors.newCachedThreadPool(new NamedThreadFactory("notify-W" + id, true));

        this.neighbours = new NeighbourTable(id);
        this.executor = new TaskExecutor(id, config.getPositiveInt("worker.threads"));
        this.overlay = new OverlayManager(self, bootstrap, neighbours, config.getPositiveInt("overlay.linksPerJoin"),
                repairExecutor, background);
        MembershipView membership = new MembershipView(self, bootstrap, neighbours,
                config.getBoolean("coordinator.includeSelf"), background);
        this.coordinator = new JobCoordinator(self, executor, membership,
                config.getPositiveInt("coordinator.maxJobsPerTerm"),
                config.getPositiveInt("coordinator.maxTaskAttempts"), dispatchPool);
        this.election = new ElectionManager(self, neighbours, coordinator, overlay, messagePool,
                config.getPositiveInt("election.timeoutMs"), config.getPositiveInt("election.hopMarginMs"),
                config.getInt("election.jitterMaxMs"), config.getLong("election.seenTtlMs"));
        this.service = new WorkerServiceImpl(self, neighbours, overlay, election, coordinator, executor,
                JobLimits.from(config));
        this.heartbeat = new HeartbeatMonitor(self, neighbours, election, coordinator, overlay, bootstrap,
                config.getPositiveInt("worker.heartbeatIntervalMs"),
                config.getPositiveInt("worker.leaseRenewIntervalMs"),
                config.getPositiveInt("election.staleChecks"));
    }

    public static void main(String[] args) {
        Log.setNode("Worker ?");
        try {
            Config config = Config.load(args, Config.Role.WORKER);
            Log.setDebug(config.getBoolean("log.debug"));
            WorkerNode node = launch(config);
            node.awaitShutdown();
        } catch (ConfigException e) {
            Log.error("Configuration error: %s", e.getMessage());
            System.exit(2);
        } catch (RegistrationException e) {
            Log.error("Registration refused: %s", e.getMessage());
            System.exit(1);
        } catch (RemoteException e) {
            Log.error("Could not reach the bootstrap node (%s). Is it running, and are bootstrap.host/port "
                    + "correct?", Log.describe(e));
            System.exit(1);
        }
    }

    /** Creates, exports and starts a worker (blocks only while joining). */
    public static WorkerNode launch(Config config) throws RemoteException, RegistrationException {
        RmiSupport.configure(config);
        BootstrapClient bootstrap = BootstrapClient.fromConfig(config);
        int id = obtainId(config, bootstrap);
        Log.setNode("Worker " + id);
        WorkerNode node = new WorkerNode(config, bootstrap, id);
        node.start();
        return node;
    }

    private static int obtainId(Config config, BootstrapClient bootstrap) throws RemoteException {
        int requested = config.getInt("worker.id");
        if (requested < 0) {
            throw new ConfigException("worker.id must be positive (or 0 to have one assigned)");
        }
        int retries = config.getPositiveInt("worker.joinRetries");
        long delay = config.getPositiveInt("worker.joinRetryDelayMs");
        for (int attempt = 1; ; attempt++) {
            try {
                return requested > 0 ? requested : bootstrap.reserveWorkerId();
            } catch (RemoteException e) {
                if (attempt >= retries) {
                    throw e;
                }
                Log.info("Waiting for the bootstrap node at %s (%s)...", bootstrap.getAddress(), Log.describe(e));
                Concurrency.sleep(delay);
            }
        }
    }

    private void start() throws RemoteException, RegistrationException {
        WorkerService stub = RmiSupport.export(service, config.getInt("worker.port"), config);
        self.publish(new WorkerRef(self.id(), self.host(), stub));
        Log.info("Worker %d exported (reachable at %s; settings: %s)", self.id(), self.host(), config.getSource());

        CoordinatorInfo learned;
        try {
            learned = joinWithRetry();
        } catch (RemoteException | RegistrationException e) {
            RmiSupport.unexportQuietly(service);
            throw e;
        }
        election.adoptView(learned);
        heartbeat.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-W" + self.id()));

        if (!learned.hasCoordinator()) {
            election.startElection("I joined and no coordinator is active", neighbours.size() > 0);
        }
        if (config.getBoolean("worker.console")) {
            Thread console = new Thread(this::runConsole, "console-W" + self.id());
            console.setDaemon(true);
            console.start();
        }
    }

    /** Retries only while the bootstrap node is unreachable (e.g. started a moment later). */
    private CoordinatorInfo joinWithRetry() throws RemoteException, RegistrationException {
        int retries = config.getPositiveInt("worker.joinRetries");
        long delay = config.getPositiveInt("worker.joinRetryDelayMs");
        for (int attempt = 1; ; attempt++) {
            try {
                return overlay.join();
            } catch (RemoteException e) {
                // (a RegistrationException - e.g. duplicate ID - is not retried: it will not fix itself)
                if (attempt >= retries) {
                    throw e;
                }
                Log.info("Waiting for the bootstrap node at %s (%s)...", bootstrap.getAddress(), Log.describe(e));
                Concurrency.sleep(delay);
            }
        }
    }

    // ------------------------------------------------------------------ console

    private void runConsole() {
        Log.info("Type 'help' for worker console commands.");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim().toLowerCase();
                switch (cmd) {
                    case "":
                        break;
                    case "status":
                    case "s":
                        printStatus();
                        break;
                    case "neighbours":
                    case "neighbors":
                    case "n":
                        System.out.println("Neighbours: " + neighbours.ids());
                        break;
                    case "elect":
                        election.startElection("manual request from the console", false);
                        break;
                    case "quit":
                    case "exit":
                        shutdown();
                        System.exit(0);
                        return;
                    default:
                        System.out.println("Commands: status | neighbours | elect | quit");
                }
            }
        } catch (IOException e) {
            Log.debug("Console closed: %s", e.getMessage());
        }
    }

    private void printStatus() {
        WorkerStatus s = service.getStatus();
        System.out.printf("Worker %d @ %s | role: %s | JAC %d | term %d | coordinator: %s | jobs this term %d/%d%n"
                        + "  neighbours %s | threads %d | tasks running %d (peak %d), completed %d"
                        + " | elections processed %d, duplicates ignored %d%n",
                s.getId(), s.getHost(), s.role(), s.getJac(), s.getTerm(),
                s.getCoordinatorId() < 0 ? "none" : "Worker " + s.getCoordinatorId(),
                s.getJobsInTerm(), s.getMaxJobsPerTerm(), s.getNeighbours(), s.getThreads(),
                s.getRunningTasks(), s.getPeakConcurrentTasks(), s.getCompletedTasks(),
                s.getElectionsProcessed(), s.getDuplicatesIgnored());
    }

    // ------------------------------------------------------------------ shutdown

    /** Graceful leave: stop monitors, unregister, tell neighbours, release threads. */
    public void shutdown() {
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        Log.info("Worker %d leaving the system", self.id());
        heartbeat.stop();
        boolean wasCoordinator = coordinator.isActive();
        if (wasCoordinator) {
            coordinator.relinquishRole("shutting down");
        }
        List<WorkerRef> formerNeighbours = overlay.leave();
        if (wasCoordinator) {
            // Hand over quickly: ask a former neighbour to elect a new coordinator now,
            // instead of waiting for the others' heartbeats to notice we are gone.
            for (WorkerRef n : formerNeighbours) {
                try {
                    n.getStub().requestElection("the coordinator Worker " + self.id() + " is leaving");
                    break;
                } catch (RemoteException e) {
                    Log.debug("Could not ask %s to hold an election", n);
                }
            }
        }
        RmiSupport.unexportQuietly(service);
        executor.shutdown();
        Concurrency.shutdown(messagePool, 500);
        Concurrency.shutdown(dispatchPool, 500);
        Concurrency.shutdown(repairExecutor, 500);
        Concurrency.shutdown(background, 500);
        stopLatch.countDown();
    }

    public void awaitShutdown() {
        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

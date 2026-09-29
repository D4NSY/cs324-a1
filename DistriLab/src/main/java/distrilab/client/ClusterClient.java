package distrilab.client;

import distrilab.api.DistriLabException;
import distrilab.api.InvalidJobException;
import distrilab.api.JobExecutionException;
import distrilab.api.NotCoordinatorException;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.JobResult;
import distrilab.api.model.WorkerRef;
import distrilab.api.model.WorkerStatus;
import distrilab.jobs.Job;
import distrilab.net.BootstrapClient;
import distrilab.util.Concurrency;
import distrilab.util.Config;
import distrilab.util.Log;

import java.lang.management.ManagementFactory;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Client-side access to the cluster, shared by the GUI and the command-line client.
 * <p>
 * Finding the coordinator: ask the bootstrap node for the active workers, ask one of them
 * who the coordinator is, and check that the coordinator's term is open. Jobs are then
 * sent straight to the coordinator over RMI.
 * <p>
 * Coordinators change (every 5 jobs, or after a failure), so {@link #submit} retries:
 * on {@link NotCoordinatorException} it follows the hint in the exception; on a
 * RemoteException (coordinator crashed) it rediscovers. Jobs are pure computations, so
 * re-sending one after a crash is safe.
 * <p>
 * Thread-safe: the GUI submits many jobs concurrently through one instance.
 */
public final class ClusterClient {

    /** Receives progress messages for one submission. */
    @FunctionalInterface
    public interface Progress {
        void update(String message);

        Progress NONE = message -> { };
    }

    private final BootstrapClient bootstrap;
    private final String clientId;
    private final int maxAttempts;
    private final long retryDelayMs;
    private volatile WorkerRef cachedCoordinator;
    private volatile List<WorkerRef> lastKnownWorkers = Collections.emptyList();

    public ClusterClient(BootstrapClient bootstrap, int maxAttempts, long retryDelayMs) {
        this.bootstrap = bootstrap;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
        // RuntimeMXBean name is "pid@hostname" (works on Java 8+); a random suffix keeps IDs unique.
        String process = ManagementFactory.getRuntimeMXBean().getName();
        int at = process.indexOf('@');
        this.clientId = "client-" + (at > 0 ? process.substring(0, at) : "x") + "-"
                + Integer.toHexString(ThreadLocalRandom.current().nextInt(0x1000, 0xFFFF));
    }

    public static ClusterClient fromConfig(Config config) {
        return new ClusterClient(BootstrapClient.fromConfig(config),
                config.getPositiveInt("client.maxSubmitAttempts"), config.getPositiveInt("client.retryDelayMs"));
    }

    public String getClientId() {
        return clientId;
    }

    public String getBootstrapAddress() {
        return bootstrap.getAddress();
    }

    /** Submits a job and waits for its result, retrying across coordinator changes. */
    public JobResult submit(Job job, Progress progress)
            throws InvalidJobException, JobExecutionException, DistriLabException, InterruptedException {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Submission cancelled");
            }
            WorkerRef coordinator = cachedCoordinator;
            if (coordinator == null) {
                coordinator = discoverCoordinator(progress);
            }
            if (coordinator == null) {
                progress.update("Waiting for a coordinator (election in progress)");
                pause(retryDelayMs);
                continue;
            }
            try {
                progress.update("Running on coordinator Worker " + coordinator.getId());
                return coordinator.getStub().submitJob(job, clientId);
            } catch (NotCoordinatorException e) {
                CoordinatorInfo hint = e.getHint();
                boolean useful = hint.hasCoordinator() && hint.getCoordinatorId() != coordinator.getId();
                cachedCoordinator = useful ? hint.getCoordinatorRef() : null;
                progress.update("Worker " + coordinator.getId() + " is not accepting jobs - "
                        + (useful ? "redirecting to Worker " + hint.getCoordinatorId() : "waiting for the next election"));
                // A short pause even when redirecting: during a hand-over two workers can briefly
                // point at each other, and we must not burn through all attempts in a few ms.
                pause(useful ? retryDelayMs / 4 : retryDelayMs);
            } catch (RemoteException e) {
                cachedCoordinator = null;
                progress.update("Coordinator Worker " + coordinator.getId() + " unreachable ("
                        + Log.describe(e) + ") - finding the new coordinator");
                pause(retryDelayMs);
            }
        }
        throw new DistriLabException("No coordinator accepted the job after " + maxAttempts + " attempts");
    }

    /**
     * Finds the active coordinator, or returns null (after asking a worker to start an
     * election) if there is none right now.
     */
    public WorkerRef discoverCoordinator(Progress progress) {
        List<WorkerRef> workers = workersForDiscovery(progress);
        if (workers.isEmpty()) {
            return null;
        }
        WorkerRef contact = null;
        int asked = 0;
        for (WorkerRef worker : workers) {
            if (asked >= 3) {
                break;
            }
            CoordinatorInfo info;
            try {
                info = worker.getStub().getCoordinatorInfo();
            } catch (RemoteException e) {
                continue; // that worker is down; try another
            }
            asked++;
            if (contact == null) {
                contact = worker;
            }
            if (info.hasCoordinator()) {
                WorkerRef candidate = info.getCoordinatorRef();
                try {
                    if (candidate.getStub().isActiveCoordinator()) {
                        cachedCoordinator = candidate;
                        return candidate;
                    }
                } catch (RemoteException e) {
                    // the coordinator this worker knows about is gone
                }
            }
        }
        if (contact != null) {
            try {
                contact.getStub().requestElection("client " + clientId + " found no active coordinator");
            } catch (RemoteException e) {
                Log.debug("Could not ask %s to start an election: %s", contact, Log.describe(e));
            }
        }
        return null;
    }

    private List<WorkerRef> workersForDiscovery(Progress progress) {
        List<WorkerRef> workers;
        try {
            workers = new ArrayList<>(bootstrap.activeWorkers());
            lastKnownWorkers = workers;
        } catch (RemoteException e) {
            workers = new ArrayList<>(lastKnownWorkers);
            progress.update("Bootstrap node unreachable (" + Log.describe(e) + ")"
                    + (workers.isEmpty() ? "" : " - using previously known workers"));
        }
        if (workers.isEmpty()) {
            progress.update("No workers are registered yet");
        }
        Collections.shuffle(workers);
        return workers;
    }

    /** Current coordinator as seen by the cluster (for status displays); never throws. */
    public CoordinatorInfo currentCoordinatorInfo() {
        for (WorkerRef worker : workersForDiscovery(Progress.NONE)) {
            try {
                return worker.getStub().getCoordinatorInfo();
            } catch (RemoteException e) {
                // try the next one
            }
        }
        return null;
    }

    /** Status of every registered worker (unreachable workers are skipped). */
    public List<WorkerStatus> networkStatus() throws RemoteException {
        List<WorkerStatus> statuses = new ArrayList<>();
        for (WorkerRef worker : bootstrap.activeWorkers()) {
            try {
                statuses.add(worker.getStub().getStatus());
            } catch (RemoteException e) {
                Log.debug("%s did not answer the status request", worker);
            }
        }
        return statuses;
    }

    private static void pause(long millis) throws InterruptedException {
        if (!Concurrency.sleep(millis)) {
            throw new InterruptedException("Submission cancelled");
        }
    }
}

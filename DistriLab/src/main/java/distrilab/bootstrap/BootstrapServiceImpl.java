package distrilab.bootstrap;

import distrilab.api.BootstrapService;
import distrilab.api.RegistrationException;
import distrilab.api.model.RegistrationResult;
import distrilab.api.model.WorkerRef;
import distrilab.util.Log;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The bootstrap node's membership directory.
 * <p>
 * Membership is <i>soft state</i>: each worker holds a lease that it renews periodically;
 * a lease that is not renewed expires and the worker is dropped. A restarted bootstrap
 * therefore rebuilds its directory from the next round of renewals.
 * <p>
 * Thread safety: RMI may run calls from many workers at the same time. The directory is
 * a {@link ConcurrentHashMap}; the compound "check ID is free, pick a random peer, insert"
 * step of registration is additionally guarded by {@code membershipLock} so two workers
 * can never register the same ID.
 */
public final class BootstrapServiceImpl implements BootstrapService {

    /** A registered worker and when it last renewed its lease. */
    private static final class Lease {
        final WorkerRef worker;
        volatile long renewedAt;

        Lease(WorkerRef worker) {
            this.worker = worker;
            this.renewedAt = System.currentTimeMillis();
        }
    }

    private final Map<Integer, Lease> directory = new ConcurrentHashMap<>();
    private final Object membershipLock = new Object();
    private final AtomicInteger highestId = new AtomicInteger();
    private final long leaseTimeoutMs;
    private final ExecutorService verifier;

    public BootstrapServiceImpl(long leaseTimeoutMs, ExecutorService verifier) {
        this.leaseTimeoutMs = leaseTimeoutMs;
        this.verifier = verifier;
    }

    @Override
    public int reserveWorkerId() {
        synchronized (membershipLock) {
            int id;
            do {
                id = highestId.incrementAndGet();
            } while (directory.containsKey(id));
            Log.info("Reserved worker ID %d", id);
            return id;
        }
    }

    @Override
    public RegistrationResult registerWorker(WorkerRef worker) throws RegistrationException {
        if (worker == null) {
            throw new RegistrationException("No worker reference supplied");
        }
        int id = worker.getId();
        Lease existing = directory.get(id);
        // Liveness is checked with a remote call, so it is done OUTSIDE the lock.
        if (existing != null) {
            if (isAlive(existing.worker)) {
                throw new RegistrationException("Worker ID " + id + " is already used by an active worker on "
                        + existing.worker.getHost() + ". Choose another --id.");
            }
            directory.remove(id, existing);
            Log.info("Replaced stale registration of Worker %d (old process is not responding)", id);
        }
        synchronized (membershipLock) {
            if (directory.containsKey(id)) {
                throw new RegistrationException("Worker ID " + id + " was registered concurrently by another worker");
            }
            WorkerRef peer = pickRandom(activeLeases(), null); // chosen BEFORE the newcomer is added
            directory.put(id, new Lease(worker));
            highestId.accumulateAndGet(id, Math::max);
            int active = directory.size();
            Log.info("Registered Worker %d at %s. Random peer for it: %s. Active workers: %s",
                    id, worker.getHost(), peer == null ? "none (first worker)" : peer, idsOf(activeLeases()));
            return new RegistrationResult(peer, active);
        }
    }

    @Override
    public boolean renewLease(WorkerRef worker) {
        Lease lease = directory.get(worker.getId());
        if (lease != null) {
            lease.renewedAt = System.currentTimeMillis();
            return true;
        }
        synchronized (membershipLock) {
            directory.putIfAbsent(worker.getId(), new Lease(worker));
            highestId.accumulateAndGet(worker.getId(), Math::max);
        }
        Log.info("Worker %d re-registered through its lease renewal (bootstrap had no record of it)", worker.getId());
        return false;
    }

    @Override
    public void unregisterWorker(int workerId) {
        if (directory.remove(workerId) != null) {
            Log.info("Worker %d left the system. Active workers: %s", workerId, idsOf(activeLeases()));
        }
    }

    @Override
    public List<WorkerRef> getActiveWorkers() {
        return activeLeases().stream().map(l -> l.worker)
                .sorted(Comparator.comparingInt(WorkerRef::getId))
                .collect(Collectors.toList());
    }

    @Override
    public WorkerRef getRandomWorker(Set<Integer> excludeIds) {
        return pickRandom(activeLeases(), excludeIds);
    }

    @Override
    public void reportSuspectedFailure(int workerId) {
        Lease lease = directory.get(workerId);
        if (lease == null) {
            return;
        }
        // Verify asynchronously so the reporting worker is not kept waiting.
        verifier.execute(() -> {
            if (!isAlive(lease.worker) && directory.remove(workerId, lease)) {
                Log.warn("Worker %d was reported unreachable and did not answer a ping - removed. Active: %s",
                        workerId, idsOf(activeLeases()));
            }
        });
    }

    /** Removes workers whose lease has expired. Called periodically by the bootstrap node. */
    void expireLeases() {
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Lease> e : directory.entrySet()) {
            if (now - e.getValue().renewedAt > leaseTimeoutMs && directory.remove(e.getKey(), e.getValue())) {
                Log.warn("Lease of Worker %d expired (no renewal for %d ms) - removed. Active: %s",
                        e.getKey(), now - e.getValue().renewedAt, idsOf(activeLeases()));
            }
        }
    }

    /** Human-readable directory listing for the console. */
    String describe() {
        List<Lease> leases = new ArrayList<>(activeLeases());
        if (leases.isEmpty()) {
            return "No active workers.";
        }
        leases.sort(Comparator.comparingInt(l -> l.worker.getId()));
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder(String.format("%-8s %-18s %s%n", "Worker", "Host", "Last lease renewal"));
        for (Lease l : leases) {
            sb.append(String.format("%-8d %-18s %d ms ago%n", l.worker.getId(), l.worker.getHost(), now - l.renewedAt));
        }
        return sb.toString().trim();
    }

    private List<Lease> activeLeases() {
        long now = System.currentTimeMillis();
        return directory.values().stream()
                .filter(l -> now - l.renewedAt <= leaseTimeoutMs)
                .collect(Collectors.toList());
    }

    private static WorkerRef pickRandom(List<Lease> leases, Set<Integer> exclude) {
        List<WorkerRef> candidates = leases.stream().map(l -> l.worker)
                .filter(w -> exclude == null || !exclude.contains(w.getId()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return null;
        }
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private static boolean isAlive(WorkerRef worker) {
        try {
            return worker.getStub().ping();
        } catch (RemoteException e) {
            return false;
        }
    }

    private static List<Integer> idsOf(List<Lease> leases) {
        return leases.stream().map(l -> l.worker.getId()).sorted().collect(Collectors.toList());
    }
}

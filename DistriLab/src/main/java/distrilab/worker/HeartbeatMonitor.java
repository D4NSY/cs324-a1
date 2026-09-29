package distrilab.worker;

import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.WorkerRef;
import distrilab.net.BootstrapClient;
import distrilab.util.Concurrency;
import distrilab.util.Log;
import distrilab.util.NamedThreadFactory;

import java.rmi.RemoteException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Periodic background checks (failure detection):
 * <ul>
 *   <li>ping each neighbour - an unreachable neighbour is removed and the overlay repaired;</li>
 *   <li>check the coordinator - if it is unreachable, or stays closed with no successor,
 *       start an election ("when no coordinator is active, any worker may initiate");</li>
 *   <li>renew this worker's lease at the bootstrap node;</li>
 *   <li>purge old message IDs from the duplicate filters.</li>
 * </ul>
 */
public final class HeartbeatMonitor {

    private final NodeIdentity self;
    private final NeighbourTable neighbours;
    private final ElectionManager election;
    private final CoordinatorRole role;
    private final NeighbourFailureListener failureListener;
    private final BootstrapClient bootstrap;
    private final long heartbeatMs;
    private final long leaseRenewMs;
    private final int staleChecksLimit;
    private final ScheduledExecutorService scheduler;
    /** Consecutive checks that found the coordinator's term closed (atomic: runs may use different pool threads). */
    private final AtomicInteger staleChecks = new AtomicInteger();
    private volatile boolean bootstrapWarningShown;

    public HeartbeatMonitor(NodeIdentity self, NeighbourTable neighbours, ElectionManager election,
                            CoordinatorRole role, NeighbourFailureListener failureListener,
                            BootstrapClient bootstrap, long heartbeatMs, long leaseRenewMs, int staleChecksLimit) {
        this.self = self;
        this.neighbours = neighbours;
        this.election = election;
        this.role = role;
        this.failureListener = failureListener;
        this.bootstrap = bootstrap;
        this.heartbeatMs = heartbeatMs;
        this.leaseRenewMs = leaseRenewMs;
        this.staleChecksLimit = staleChecksLimit;
        this.scheduler = Executors.newScheduledThreadPool(2, new NamedThreadFactory("heartbeat-W" + self.id(), true));
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(Concurrency.guarded("neighbour-check", this::checkNeighbours),
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(Concurrency.guarded("coordinator-check", this::checkCoordinator),
                heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(Concurrency.guarded("lease-renewal", this::renewLease),
                leaseRenewMs, leaseRenewMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(Concurrency.guarded("purge", election::purgeOldMessageIds),
                60, 60, TimeUnit.SECONDS);
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void checkNeighbours() {
        for (WorkerRef neighbour : neighbours.snapshot()) {
            boolean alive;
            try {
                alive = neighbour.getStub().ping();
            } catch (RemoteException e) {
                alive = false;
            }
            if (!alive) {
                failureListener.neighbourUnreachable(neighbour);
            }
        }
    }

    /** No lock is held here: the remote isActiveCoordinator() call must never run under a lock. */
    private void checkCoordinator() {
        if (election.electionInProgress()) {
            staleChecks.set(0);
            return; // let the running election finish
        }
        CoordinatorInfo view = election.currentView();
        if (!view.hasCoordinator()) {
            election.startElection("no active coordinator is known", true);
            return;
        }
        boolean active;
        if (view.getCoordinatorId() == self.id()) {
            active = role.isActive();
        } else {
            try {
                active = view.getCoordinatorRef().getStub().isActiveCoordinator();
            } catch (RemoteException e) {
                Log.warn("Coordinator %s is unreachable (%s)", view, Log.describe(e));
                election.coordinatorFailed(view.getCoordinatorId());
                election.startElection("coordinator Worker " + view.getCoordinatorId() + " failed", true);
                staleChecks.set(0);
                return;
            }
        }
        if (active) {
            staleChecks.set(0);
        } else if (staleChecks.incrementAndGet() >= staleChecksLimit) {
            staleChecks.set(0);
            election.startElection("coordinator Worker " + view.getCoordinatorId()
                    + " has closed its term and no successor was announced", true);
        }
    }

    private void renewLease() {
        try {
            if (!bootstrap.renewLease(self.ref())) {
                Log.info("The bootstrap node had lost my registration (restarted?) - re-registered");
            }
            if (bootstrapWarningShown) {
                Log.info("Bootstrap node reachable again");
                bootstrapWarningShown = false;
            }
        } catch (RemoteException e) {
            if (!bootstrapWarningShown) {
                Log.warn("Bootstrap node unreachable (%s). Elections and jobs continue; new workers cannot join "
                        + "until it is back.", Log.describe(e));
                bootstrapWarningShown = true;
            }
        }
    }
}

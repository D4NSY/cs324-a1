package distrilab.worker;

import distrilab.api.RegistrationException;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.RegistrationResult;
import distrilab.api.model.WorkerRef;
import distrilab.net.BootstrapClient;
import distrilab.util.Log;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Builds and maintains this worker's place in the unstructured overlay network.
 * <ul>
 *   <li><b>Join</b>: register with the bootstrap node, which returns a random active
 *       worker; link to it (and, if configured, to further random workers).</li>
 *   <li><b>Repair</b>: when a neighbour fails or leaves, drop it and link to another
 *       random active worker so the network stays connected.</li>
 *   <li><b>Leave</b>: unregister and tell the neighbours.</li>
 * </ul>
 * Links are bidirectional: both ends store each other in their {@link NeighbourTable}.
 */
public final class OverlayManager implements NeighbourFailureListener {

    private static final int REPAIR_ATTEMPTS = 3;

    private final NodeIdentity self;
    private final BootstrapClient bootstrap;
    private final NeighbourTable neighbours;
    private final int linksPerJoin;
    /** Single-threaded: repairs happen one at a time, in order. */
    private final ExecutorService repairExecutor;
    private final ExecutorService background;

    public OverlayManager(NodeIdentity self, BootstrapClient bootstrap, NeighbourTable neighbours, int linksPerJoin,
                          ExecutorService repairExecutor, ExecutorService background) {
        this.self = self;
        this.bootstrap = bootstrap;
        this.neighbours = neighbours;
        this.linksPerJoin = Math.max(1, linksPerJoin);
        this.repairExecutor = repairExecutor;
        this.background = background;
    }

    /**
     * Joins the overlay. Returns the coordinator view learned from the first neighbour
     * (empty if this is the first worker in the system).
     */
    public CoordinatorInfo join() throws RemoteException, RegistrationException {
        RegistrationResult registration = bootstrap.register(self.ref());
        Log.info("Registered with the bootstrap node (%d active worker(s) including me)",
                registration.getActiveWorkers());

        Set<Integer> tried = new HashSet<>();
        tried.add(self.id());
        WorkerRef peer = registration.getRandomPeer();
        WorkerRef firstNeighbour = null;
        while (peer != null && firstNeighbour == null) {
            tried.add(peer.getId());
            if (link(peer)) {
                firstNeighbour = peer;
                Log.info("Joined the overlay: randomly connected to active %s", peer);
            } else {
                reportAsync(peer);
                peer = bootstrap.randomWorker(tried);
            }
        }
        if (firstNeighbour == null) {
            Log.info("No other active workers - I am the first worker in the system");
            return CoordinatorInfo.none(0);
        }
        // Optional extra random links give the overlay cycles (more than one path between workers).
        for (int extra = 1; extra < linksPerJoin; extra++) {
            WorkerRef another = bootstrap.randomWorker(excludeCurrentAnd(tried));
            if (another == null) {
                break;
            }
            tried.add(another.getId());
            if (link(another)) {
                Log.info("Extra random link to %s", another);
            }
        }
        Log.info("My neighbours: %s", neighbours.ids());
        try {
            return firstNeighbour.getStub().getCoordinatorInfo();
        } catch (RemoteException e) {
            return CoordinatorInfo.none(0);
        }
    }

    /** Handles an incoming link request from another worker (remote addNeighbour). */
    public boolean acceptLink(WorkerRef from) {
        if (from == null || from.getId() == self.id()) {
            return false;
        }
        if (neighbours.add(from)) {
            Log.info("%s linked to me. My neighbours: %s", from, neighbours.ids());
        }
        return true;
    }

    /** A neighbour told us it is leaving. */
    public void neighbourLeft(int workerId) {
        if (neighbours.remove(workerId)) {
            Log.info("Worker %d left. My neighbours: %s", workerId, neighbours.ids());
            repairExecutor.execute(this::repair);
        }
    }

    @Override
    public void neighbourUnreachable(WorkerRef neighbour) {
        if (neighbours.remove(neighbour.getId())) {
            Log.warn("Neighbour %s is unreachable - removed it. My neighbours: %s", neighbour, neighbours.ids());
            reportAsync(neighbour);
            repairExecutor.execute(this::repair);
        }
    }

    /**
     * Graceful leave: unregister and let the neighbours know.
     *
     * @return the neighbours that were told (e.g. to ask one of them to hold an election)
     */
    public List<WorkerRef> leave() {
        try {
            bootstrap.unregister(self.id());
        } catch (RemoteException e) {
            Log.warn("Could not unregister from the bootstrap node: %s", Log.describe(e));
        }
        List<WorkerRef> told = new ArrayList<>();
        for (WorkerRef n : neighbours.snapshot()) {
            try {
                n.getStub().removeNeighbour(self.id());
                told.add(n);
            } catch (RemoteException e) {
                Log.debug("Could not notify %s of my departure", n);
            }
        }
        return told;
    }

    private void repair() {
        for (int attempt = 1; attempt <= REPAIR_ATTEMPTS; attempt++) {
            WorkerRef candidate;
            try {
                candidate = bootstrap.randomWorker(excludeCurrentAnd(null));
            } catch (RemoteException e) {
                Log.warn("Cannot repair the overlay right now - bootstrap node unreachable (%s)", Log.describe(e));
                return;
            }
            if (candidate == null) {
                Log.info("Overlay repair: no other active worker to link to");
                return;
            }
            if (link(candidate)) {
                Log.info("Overlay repaired: linked to %s. My neighbours: %s", candidate, neighbours.ids());
                return;
            }
            reportAsync(candidate);
        }
    }

    private boolean link(WorkerRef peer) {
        try {
            if (peer.getStub().addNeighbour(self.ref())) {
                neighbours.add(peer);
                return true;
            }
            return false;
        } catch (RemoteException e) {
            Log.warn("Could not link to %s: %s", peer, Log.describe(e));
            return false;
        }
    }

    private Set<Integer> excludeCurrentAnd(Set<Integer> more) {
        Set<Integer> exclude = new HashSet<>(neighbours.ids());
        exclude.add(self.id());
        if (more != null) {
            exclude.addAll(more);
        }
        return exclude;
    }

    private void reportAsync(WorkerRef worker) {
        background.execute(() -> {
            try {
                bootstrap.reportSuspectedFailure(worker.getId());
            } catch (RemoteException e) {
                Log.debug("Could not report %s: %s", worker, Log.describe(e));
            }
        });
    }
}

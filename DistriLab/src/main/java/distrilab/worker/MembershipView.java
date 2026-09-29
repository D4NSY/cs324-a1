package distrilab.worker;

import distrilab.api.model.WorkerRef;
import distrilab.net.BootstrapClient;
import distrilab.util.Log;

import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;

/**
 * Which workers are available to share a job. The authoritative list comes from the
 * bootstrap node; the last successful answer is cached so that job processing keeps
 * working (with a slightly stale list) if the bootstrap node is down.
 */
public final class MembershipView {

    private final NodeIdentity self;
    private final BootstrapClient bootstrap;
    private final NeighbourTable neighbours;
    private final boolean includeSelf;
    private final ExecutorService background;
    private volatile List<WorkerRef> lastKnown = Collections.emptyList();

    public MembershipView(NodeIdentity self, BootstrapClient bootstrap, NeighbourTable neighbours,
                          boolean includeSelf, ExecutorService background) {
        this.self = self;
        this.bootstrap = bootstrap;
        this.neighbours = neighbours;
        this.includeSelf = includeSelf;
        this.background = background;
    }

    /** Available workers sorted by ID (so part 1 goes to the lowest ID, as in the brief's example). */
    public List<WorkerRef> availableWorkers() {
        TreeMap<Integer, WorkerRef> byId = new TreeMap<>();
        try {
            List<WorkerRef> fresh = bootstrap.activeWorkers();
            lastKnown = fresh;
            fresh.forEach(w -> byId.put(w.getId(), w));
        } catch (RemoteException e) {
            Log.warn("Bootstrap node unreachable (%s) - using the last known membership plus my neighbours",
                    Log.describe(e));
            lastKnown.forEach(w -> byId.put(w.getId(), w));
            neighbours.snapshot().forEach(w -> byId.put(w.getId(), w));
        }
        if (includeSelf) {
            byId.put(self.id(), self.ref());
        } else {
            byId.remove(self.id());
            if (byId.isEmpty()) {
                byId.put(self.id(), self.ref()); // nobody else - do the work ourselves
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Tells the bootstrap node (in the background) that a worker did not answer. */
    public void reportUnreachable(WorkerRef worker) {
        lastKnown = removeFrom(lastKnown, worker.getId());
        background.execute(() -> {
            try {
                bootstrap.reportSuspectedFailure(worker.getId());
            } catch (RemoteException e) {
                Log.debug("Could not report %s to the bootstrap node: %s", worker, Log.describe(e));
            }
        });
    }

    private static List<WorkerRef> removeFrom(List<WorkerRef> list, int id) {
        List<WorkerRef> copy = new ArrayList<>(list);
        copy.removeIf(w -> w.getId() == id);
        return Collections.unmodifiableList(copy);
    }
}

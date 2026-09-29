package distrilab.worker;

import distrilab.api.model.WorkerRef;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * This worker's neighbours in the unstructured overlay. Read by election threads,
 * heartbeat threads and RMI threads at the same time, so it is backed by a
 * {@link ConcurrentHashMap}; callers iterate over snapshots, never the live map.
 */
public final class NeighbourTable {

    private final int selfId;
    private final ConcurrentHashMap<Integer, WorkerRef> neighbours = new ConcurrentHashMap<>();

    public NeighbourTable(int selfId) {
        this.selfId = selfId;
    }

    /** Adds a neighbour; returns true if it was not already present. Self-links are ignored. */
    public boolean add(WorkerRef ref) {
        if (ref == null || ref.getId() == selfId) {
            return false;
        }
        return neighbours.put(ref.getId(), ref) == null;
    }

    public boolean remove(int workerId) {
        return neighbours.remove(workerId) != null;
    }

    public boolean contains(int workerId) {
        return neighbours.containsKey(workerId);
    }

    public int size() {
        return neighbours.size();
    }

    public List<WorkerRef> snapshot() {
        List<WorkerRef> list = new ArrayList<>(neighbours.values());
        list.sort(Comparator.comparingInt(WorkerRef::getId));
        return list;
    }

    /** All neighbours except the given one (the worker a message came from). */
    public List<WorkerRef> snapshotExcluding(int workerId) {
        List<WorkerRef> list = snapshot();
        list.removeIf(r -> r.getId() == workerId);
        return list;
    }

    public Set<Integer> ids() {
        return new TreeSet<>(neighbours.keySet());
    }
}

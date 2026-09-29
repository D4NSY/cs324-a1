package distrilab.worker;

import distrilab.api.model.WorkerRef;

/**
 * Who this worker is. The ID and host are known at construction; the {@link WorkerRef}
 * (which contains our own RMI stub) only exists once the worker has been exported, so it
 * is published once through a volatile field.
 */
public final class NodeIdentity {

    private final int id;
    private final String host;
    private volatile WorkerRef ref;

    public NodeIdentity(int id, String host) {
        this.id = id;
        this.host = host;
    }

    public int id() {
        return id;
    }

    public String host() {
        return host;
    }

    public WorkerRef ref() {
        WorkerRef r = ref;
        if (r == null) {
            throw new IllegalStateException("Worker " + id + " has not been exported yet");
        }
        return r;
    }

    void publish(WorkerRef exported) {
        if (exported.getId() != id) {
            throw new IllegalArgumentException("Reference is for a different worker");
        }
        this.ref = exported;
    }
}

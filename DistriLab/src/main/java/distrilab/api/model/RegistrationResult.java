package distrilab.api.model;

import java.io.Serializable;

/** What a joining worker receives from the bootstrap node. */
public final class RegistrationResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final WorkerRef randomPeer;
    private final int activeWorkers;

    public RegistrationResult(WorkerRef randomPeer, int activeWorkers) {
        this.randomPeer = randomPeer;
        this.activeWorkers = activeWorkers;
    }

    /** A randomly chosen active worker to link to, or null if this is the first worker. */
    public WorkerRef getRandomPeer() {
        return randomPeer;
    }

    /** Number of active workers including the newcomer. */
    public int getActiveWorkers() {
        return activeWorkers;
    }
}

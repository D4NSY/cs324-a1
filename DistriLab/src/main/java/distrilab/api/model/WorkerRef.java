package distrilab.api.model;

import distrilab.api.WorkerService;

import java.io.Serializable;
import java.util.Objects;

/**
 * A serialisable reference to a worker: its ID, host and RMI stub. Passing stubs
 * around (instead of host/port pairs) lets any process call a worker directly once it
 * has learned about it. Two references are equal when they name the same worker ID.
 */
public final class WorkerRef implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String host;
    private final WorkerService stub;

    public WorkerRef(int id, String host, WorkerService stub) {
        if (id <= 0) {
            throw new IllegalArgumentException("Worker IDs must be positive: " + id);
        }
        this.id = id;
        this.host = Objects.requireNonNull(host, "host");
        this.stub = Objects.requireNonNull(stub, "stub");
    }

    public int getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public WorkerService getStub() {
        return stub;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof WorkerRef && ((WorkerRef) o).id == id;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(id);
    }

    @Override
    public String toString() {
        return "Worker " + id;
    }
}

package distrilab.api.model;

import java.io.Serializable;

/** A worker's view of the current term and who coordinates it. Immutable. */
public final class CoordinatorInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int term;
    private final Candidate coordinator; // null = no coordinator known

    private CoordinatorInfo(int term, Candidate coordinator) {
        this.term = term;
        this.coordinator = coordinator;
    }

    public static CoordinatorInfo none(int term) {
        return new CoordinatorInfo(term, null);
    }

    public static CoordinatorInfo of(int term, Candidate coordinator) {
        if (coordinator == null) {
            throw new IllegalArgumentException("coordinator");
        }
        return new CoordinatorInfo(term, coordinator);
    }

    public int getTerm() {
        return term;
    }

    public boolean hasCoordinator() {
        return coordinator != null;
    }

    public Candidate getCoordinator() {
        return coordinator;
    }

    public int getCoordinatorId() {
        return coordinator == null ? -1 : coordinator.getWorkerId();
    }

    public WorkerRef getCoordinatorRef() {
        return coordinator == null ? null : coordinator.getRef();
    }

    @Override
    public String toString() {
        return coordinator == null
                ? "no coordinator (last term " + term + ")"
                : "Worker " + coordinator.getWorkerId() + " (term " + term + ")";
    }
}

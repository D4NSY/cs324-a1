package distrilab.api.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * A worker's entry in a leader election: its ID, its Job Allocation Counter (JAC)
 * at the time it was asked, and a reference so the winner can be contacted.
 * <p>
 * <b>Election rule</b>: the lowest JAC wins; if JACs are equal the highest ID wins.
 * This is a strict total order over workers, so every worker that compares the same
 * candidates reaches the same answer - the basis for agreement.
 */
public final class Candidate implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int workerId;
    private final int jac;
    private final WorkerRef ref;

    public Candidate(int workerId, int jac, WorkerRef ref) {
        if (jac < 0) {
            throw new IllegalArgumentException("JAC cannot be negative");
        }
        this.workerId = workerId;
        this.jac = jac;
        this.ref = Objects.requireNonNull(ref, "ref");
    }

    /** True if this candidate should be elected ahead of {@code other}. */
    public boolean beats(Candidate other) {
        if (other == null) {
            return true;
        }
        if (jac != other.jac) {
            return jac < other.jac;          // lowest JAC wins
        }
        return workerId > other.workerId;    // tie: highest ID wins
    }

    /** The better of two candidates (either may be null). */
    public static Candidate best(Candidate a, Candidate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.beats(b) ? a : b;
    }

    public int getWorkerId() {
        return workerId;
    }

    public int getJac() {
        return jac;
    }

    public WorkerRef getRef() {
        return ref;
    }

    @Override
    public String toString() {
        return "Worker " + workerId + " (JAC " + jac + ")";
    }
}

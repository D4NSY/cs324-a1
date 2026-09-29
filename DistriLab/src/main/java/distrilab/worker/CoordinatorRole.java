package distrilab.worker;

/**
 * What the election needs from the coordinator role: the JAC to put in the ballot, and
 * the ability to take up or give up the role when a COORDINATOR message arrives.
 * (An interface keeps ElectionManager independent of how jobs are processed.)
 */
public interface CoordinatorRole {

    /** Current Job Allocation Counter of this worker. */
    int currentJac();

    /** This worker won the election for {@code term}. */
    void assumeRole(int term);

    /** Another worker was elected for a newer term - stop accepting jobs. */
    void relinquishRole(String reason);

    /** True while this worker is coordinator and its term is still open. */
    boolean isActive();
}

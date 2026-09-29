package distrilab.api.model;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Monitoring snapshot of one worker (used by the status tools and the GUI). */
public final class WorkerStatus implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int id;
    private final String host;
    private final int jac;
    private final boolean activeCoordinator;
    private final int term;
    private final int coordinatorId;
    private final int jobsInTerm;
    private final int maxJobsPerTerm;
    private final List<Integer> neighbours;
    private final int threads;
    private final int runningTasks;
    private final int peakConcurrentTasks;
    private final long completedTasks;
    private final long electionsProcessed;
    private final long duplicatesIgnored;

    public WorkerStatus(int id, String host, int jac, boolean activeCoordinator, int term, int coordinatorId,
                        int jobsInTerm, int maxJobsPerTerm, List<Integer> neighbours, int threads,
                        int runningTasks, int peakConcurrentTasks, long completedTasks,
                        long electionsProcessed, long duplicatesIgnored) {
        this.id = id;
        this.host = host;
        this.jac = jac;
        this.activeCoordinator = activeCoordinator;
        this.term = term;
        this.coordinatorId = coordinatorId;
        this.jobsInTerm = jobsInTerm;
        this.maxJobsPerTerm = maxJobsPerTerm;
        this.neighbours = Collections.unmodifiableList(new ArrayList<>(neighbours));
        this.threads = threads;
        this.runningTasks = runningTasks;
        this.peakConcurrentTasks = peakConcurrentTasks;
        this.completedTasks = completedTasks;
        this.electionsProcessed = electionsProcessed;
        this.duplicatesIgnored = duplicatesIgnored;
    }

    public int getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public int getJac() {
        return jac;
    }

    public boolean isActiveCoordinator() {
        return activeCoordinator;
    }

    public int getTerm() {
        return term;
    }

    public int getCoordinatorId() {
        return coordinatorId;
    }

    public int getJobsInTerm() {
        return jobsInTerm;
    }

    public int getMaxJobsPerTerm() {
        return maxJobsPerTerm;
    }

    public List<Integer> getNeighbours() {
        return neighbours;
    }

    public int getThreads() {
        return threads;
    }

    public int getRunningTasks() {
        return runningTasks;
    }

    public int getPeakConcurrentTasks() {
        return peakConcurrentTasks;
    }

    public long getCompletedTasks() {
        return completedTasks;
    }

    public long getElectionsProcessed() {
        return electionsProcessed;
    }

    public long getDuplicatesIgnored() {
        return duplicatesIgnored;
    }

    /** "COORDINATOR", "COORD-CLOSED" (its term is full and the next election is pending) or "worker". */
    public String role() {
        if (activeCoordinator) {
            return "COORDINATOR";
        }
        return coordinatorId == id ? "COORD-CLOSED" : "worker";
    }
}

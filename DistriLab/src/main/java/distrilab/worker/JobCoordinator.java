package distrilab.worker;

import distrilab.api.JobExecutionException;
import distrilab.api.NotCoordinatorException;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.JobResult;
import distrilab.api.model.SubtaskReport;
import distrilab.api.model.TaskResult;
import distrilab.api.model.WorkerRef;
import distrilab.jobs.Job;
import distrilab.util.Formats;
import distrilab.util.Log;

import java.math.BigDecimal;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * The coordinator role of a worker: term bookkeeping, the Job Allocation Counter (JAC),
 * and splitting/distributing/combining jobs.
 *
 * <h3>Terms and the JAC</h3>
 * A coordinator serves one term in which it may assign at most
 * {@code coordinator.maxJobsPerTerm} (default 5) jobs. Each job it assigns increments
 * its JAC. The assignment that fills the term also closes it, and the caller then starts
 * the next election. "Is the term open? count the job, bump the JAC, close the term if
 * full" must be a single atomic step - otherwise two clients submitting at the same moment
 * could both take the 5th slot - so it runs inside {@code synchronized (termLock)}.
 *
 * <h3>Distribution</h3>
 * Each part of a job is dispatched on its own thread from {@code dispatchPool}; parts go to
 * workers in ID order. If a worker cannot be reached its part is reassigned to another
 * worker, and as a last resort computed locally, so a job survives worker crashes.
 */
public final class JobCoordinator implements CoordinatorRole {

    /** A granted job slot within a term. Immutable. */
    public static final class Slot {
        private final int term;
        private final int jobNumber;
        private final int jacAfter;
        private final boolean closesTerm;

        Slot(int term, int jobNumber, int jacAfter, boolean closesTerm) {
            this.term = term;
            this.jobNumber = jobNumber;
            this.jacAfter = jacAfter;
            this.closesTerm = closesTerm;
        }

        public int getTerm() {
            return term;
        }

        public int getJobNumber() {
            return jobNumber;
        }

        public boolean closesTerm() {
            return closesTerm;
        }
    }

    private final NodeIdentity self;
    private final TaskExecutor localExecutor;
    private final MembershipView membership;
    private final int maxJobsPerTerm;
    private final int maxTaskAttempts;
    private final ExecutorService dispatchPool;

    private final AtomicInteger jac = new AtomicInteger();
    private final Object termLock = new Object();
    // ---- guarded by termLock ----
    private boolean active;
    private int term;
    private int jobsAssignedInTerm;

    public JobCoordinator(NodeIdentity self, TaskExecutor localExecutor, MembershipView membership,
                          int maxJobsPerTerm, int maxTaskAttempts, ExecutorService dispatchPool) {
        this.self = self;
        this.localExecutor = localExecutor;
        this.membership = membership;
        this.maxJobsPerTerm = maxJobsPerTerm;
        this.maxTaskAttempts = Math.max(1, maxTaskAttempts);
        this.dispatchPool = dispatchPool;
    }

    // ------------------------------------------------------------------ CoordinatorRole

    @Override
    public int currentJac() {
        return jac.get();
    }

    @Override
    public void assumeRole(int newTerm) {
        synchronized (termLock) {
            active = true;
            term = newTerm;
            jobsAssignedInTerm = 0;
        }
        Log.info("*** I am the COORDINATOR for term %d (my JAC is %d). I may assign up to %d jobs this term. ***",
                newTerm, jac.get(), maxJobsPerTerm);
    }

    @Override
    public void relinquishRole(String reason) {
        boolean wasActive;
        synchronized (termLock) {
            wasActive = active;
            active = false;
        }
        if (wasActive) {
            Log.info("Stepping down as coordinator: %s", reason);
        }
    }

    @Override
    public boolean isActive() {
        synchronized (termLock) {
            return active;
        }
    }

    public int getJobsAssignedInTerm() {
        synchronized (termLock) {
            return jobsAssignedInTerm;
        }
    }

    public int getMaxJobsPerTerm() {
        return maxJobsPerTerm;
    }

    // ------------------------------------------------------------------ job handling

    /**
     * Atomically claims the next job slot of the current term and increments the JAC.
     *
     * @param hint this worker's view of the coordinator, returned to the client on rejection
     */
    public Slot openSlot(CoordinatorInfo hint) throws NotCoordinatorException {
        synchronized (termLock) {
            if (!active) {
                String why = term > 0 && hint.getCoordinatorId() == self.id()
                        ? "its term " + term + " is over and the next election is in progress"
                        : "the current coordinator is " + hint;
                throw new NotCoordinatorException("Worker " + self.id() + " is not accepting jobs: " + why, hint);
            }
            jobsAssignedInTerm++;
            int jacNow = jac.incrementAndGet();
            boolean closes = jobsAssignedInTerm >= maxJobsPerTerm;
            if (closes) {
                active = false; // the term is full: no further jobs are accepted
            }
            return new Slot(term, jobsAssignedInTerm, jacNow, closes);
        }
    }

    /** Splits the job, runs the parts in parallel across the available workers and combines the results. */
    public JobResult execute(Job job, String clientId, Slot slot) throws JobExecutionException {
        String jobId = "T" + slot.term + "-J" + slot.jobNumber + "@W" + self.id();
        long startNs = System.nanoTime();

        List<WorkerRef> workers = membership.availableWorkers();
        List<Job> parts = job.split(workers.size());
        Log.info("Job %s (%d/%d of term %d, JAC now %d) from %s: %s -> %d part(s): %s",
                jobId, slot.jobNumber, maxJobsPerTerm, slot.term, slot.jacAfter, clientId, job.describe(),
                parts.size(), plan(parts, workers));

        List<CompletableFuture<SubtaskReport>> futures = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            final int index = i;
            futures.add(CompletableFuture.supplyAsync(
                    () -> runPart(jobId, index, parts.get(index), workers), dispatchPool));
        }

        List<SubtaskReport> reports = new ArrayList<>(parts.size());
        try {
            for (CompletableFuture<SubtaskReport> f : futures) {
                reports.add(f.join());
            }
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            String message = cause instanceof JobExecutionException ? cause.getMessage() : Log.describe(cause);
            Log.warn("Job %s failed: %s", jobId, message);
            throw new JobExecutionException("Job " + jobId + " failed: " + message);
        }

        BigDecimal value;
        try {
            value = job.combine(reports.stream().map(SubtaskReport::getValue).collect(Collectors.toList()));
        } catch (RuntimeException e) {
            throw new JobExecutionException("Job " + jobId + " could not combine results: " + Log.describe(e));
        }
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        Log.info("Job %s complete: %s = %s in %d ms", jobId, job.describe(), Formats.number(value), elapsedMs);
        return new JobResult(jobId, job.getType(), job.describe(), value, self.id(), slot.term, slot.jobNumber,
                maxJobsPerTerm, slot.jacAfter, elapsedMs, reports);
    }

    /** Runs one part, reassigning it if the chosen worker cannot be reached. */
    private SubtaskReport runPart(String jobId, int index, Job part, List<WorkerRef> workers) {
        String taskId = jobId + "-P" + (index + 1);
        Set<Integer> failed = new HashSet<>();
        WorkerRef target = workers.get(index);
        int attempt = 0;
        while (true) {
            attempt++;
            boolean local = target.getId() == self.id() || attempt > maxTaskAttempts;
            try {
                TaskResult r = local
                        ? localExecutor.execute(taskId, part)
                        : target.getStub().executeTask(taskId, part);
                return new SubtaskReport(index + 1, r.getWorkerId(), part.describe(), r.getValue(),
                        r.getComputeMs(), attempt, r.getThreadName());
            } catch (RemoteException e) {
                failed.add(target.getId());
                membership.reportUnreachable(target);
                WorkerRef next = nextAvailable(workers, failed, index);
                Log.warn("Task %s: %s is unreachable (%s) - reassigning to %s",
                        taskId, target, Log.describe(e), next == null ? "myself" : next);
                target = next == null ? self.ref() : next;
            } catch (JobExecutionException e) {
                throw new CompletionException(e); // the computation itself failed: no point retrying
            }
        }
    }

    private WorkerRef nextAvailable(List<WorkerRef> workers, Set<Integer> failed, int startIndex) {
        for (int step = 1; step <= workers.size(); step++) {
            WorkerRef candidate = workers.get((startIndex + step) % workers.size());
            if (!failed.contains(candidate.getId())) {
                return candidate;
            }
        }
        return null;
    }

    private static String plan(List<Job> parts, List<WorkerRef> workers) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            items.add("w" + workers.get(i).getId() + ":" + parts.get(i).describe());
        }
        return String.join(" + ", items);
    }
}

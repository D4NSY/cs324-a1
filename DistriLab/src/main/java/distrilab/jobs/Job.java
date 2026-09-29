package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A computational job. Jobs are serialisable command objects: the client builds one,
 * the coordinator {@link #split splits} it into smaller jobs of the same type, each
 * worker runs {@link #compute()} on its part, and the coordinator
 * {@link #combine combines} the partial results.
 * <p>
 * Because the coordinator and workers only ever talk to this abstract type,
 * adding a new job type needs no change to the distribution code (polymorphism).
 * The equal-division algorithm lives here once ({@link #split} is a template method);
 * subclasses only say how to cut out one slice.
 */
public abstract class Job implements Serializable {
    private static final long serialVersionUID = 1L;

    public abstract JobType getType();

    /** Number of indivisible work units: list items, or integers in the range. Always >= 1. */
    public abstract long getWorkloadSize();

    /** Checks the job against the configured limits. */
    public abstract void validate(JobLimits limits) throws InvalidJobException;

    /** The part of this job covering work units [offset, offset + length). */
    protected abstract Job slice(long offset, long length);

    /** Runs the computation for this (part of a) job. Executed on a worker thread. */
    public abstract BigDecimal compute();

    /** Combines the partial results of this job's parts into the final answer. */
    public abstract BigDecimal combine(List<BigDecimal> partialResults);

    /** Short description, e.g. {@code PRIMESUM(1,250)} or {@code MAX(items 1-250)}. */
    public abstract String describe();

    /**
     * Divides the job as evenly as possible into at most {@code parts} parts: part sizes
     * differ by at most one unit, and no part is empty (so a 3-number list sent to 5
     * workers produces 3 parts).
     */
    public final List<Job> split(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1");
        }
        long total = getWorkloadSize();
        int count = (int) Math.min(parts, total);
        long base = total / count;
        long remainder = total % count;
        List<Job> result = new ArrayList<>(count);
        long offset = 0;
        for (int i = 0; i < count; i++) {
            long length = base + (i < remainder ? 1 : 0);
            result.add(slice(offset, length));
            offset += length;
        }
        return Collections.unmodifiableList(result);
    }

    protected static void requireResults(List<BigDecimal> partialResults) {
        if (partialResults == null || partialResults.isEmpty()) {
            throw new IllegalArgumentException("No partial results to combine");
        }
    }

    @Override
    public String toString() {
        return describe();
    }
}

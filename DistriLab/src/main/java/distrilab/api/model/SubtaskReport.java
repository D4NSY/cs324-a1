package distrilab.api.model;

import distrilab.util.Formats;

import java.io.Serializable;
import java.math.BigDecimal;

/** How one part of a job was executed - reported back to the client for transparency. */
public final class SubtaskReport implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int partNumber;
    private final int workerId;
    private final String description;
    private final BigDecimal value;
    private final long computeMs;
    private final int attempts;
    private final String threadName;

    public SubtaskReport(int partNumber, int workerId, String description, BigDecimal value,
                         long computeMs, int attempts, String threadName) {
        this.partNumber = partNumber;
        this.workerId = workerId;
        this.description = description;
        this.value = value;
        this.computeMs = computeMs;
        this.attempts = attempts;
        this.threadName = threadName;
    }

    public int getPartNumber() {
        return partNumber;
    }

    public int getWorkerId() {
        return workerId;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getValue() {
        return value;
    }

    public long getComputeMs() {
        return computeMs;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getThreadName() {
        return threadName;
    }

    @Override
    public String toString() {
        return String.format("part %d -> Worker %d: %s = %s  [%d ms, thread %s%s]",
                partNumber, workerId, description, Formats.number(value), computeMs, threadName,
                attempts > 1 ? ", attempt " + attempts : "");
    }
}

package distrilab.api.model;

import java.io.Serializable;
import java.math.BigDecimal;

/** Partial result returned by a worker for one part of a job. */
public final class TaskResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int workerId;
    private final BigDecimal value;
    private final long computeMs;
    private final String threadName;

    public TaskResult(int workerId, BigDecimal value, long computeMs, String threadName) {
        this.workerId = workerId;
        this.value = value;
        this.computeMs = computeMs;
        this.threadName = threadName;
    }

    public int getWorkerId() {
        return workerId;
    }

    public BigDecimal getValue() {
        return value;
    }

    public long getComputeMs() {
        return computeMs;
    }

    public String getThreadName() {
        return threadName;
    }
}

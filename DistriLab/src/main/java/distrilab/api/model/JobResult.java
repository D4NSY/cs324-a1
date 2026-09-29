package distrilab.api.model;

import distrilab.jobs.JobType;
import distrilab.util.Formats;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** Final result of a job plus how it was distributed. Immutable. */
public final class JobResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String jobId;
    private final JobType type;
    private final String description;
    private final BigDecimal value;
    private final int coordinatorId;
    private final int term;
    private final int jobNumberInTerm;
    private final int maxJobsPerTerm;
    private final int coordinatorJac;
    private final long elapsedMs;
    private final List<SubtaskReport> subtasks;

    public JobResult(String jobId, JobType type, String description, BigDecimal value, int coordinatorId,
                     int term, int jobNumberInTerm, int maxJobsPerTerm, int coordinatorJac, long elapsedMs,
                     List<SubtaskReport> subtasks) {
        this.jobId = jobId;
        this.type = type;
        this.description = description;
        this.value = value;
        this.coordinatorId = coordinatorId;
        this.term = term;
        this.jobNumberInTerm = jobNumberInTerm;
        this.maxJobsPerTerm = maxJobsPerTerm;
        this.coordinatorJac = coordinatorJac;
        this.elapsedMs = elapsedMs;
        this.subtasks = Collections.unmodifiableList(new ArrayList<>(subtasks));
    }

    public String getJobId() {
        return jobId;
    }

    public JobType getType() {
        return type;
    }

    public String getDescription() {
        return description;
    }

    public BigDecimal getValue() {
        return value;
    }

    public String getValueText() {
        return Formats.number(value);
    }

    public int getCoordinatorId() {
        return coordinatorId;
    }

    public int getTerm() {
        return term;
    }

    public int getJobNumberInTerm() {
        return jobNumberInTerm;
    }

    public int getMaxJobsPerTerm() {
        return maxJobsPerTerm;
    }

    public int getCoordinatorJac() {
        return coordinatorJac;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public List<SubtaskReport> getSubtasks() {
        return subtasks;
    }

    /** Distinct worker IDs that computed parts of this job. */
    public List<Integer> getWorkerIds() {
        return subtasks.stream().map(SubtaskReport::getWorkerId).distinct().sorted().collect(Collectors.toList());
    }

    /** e.g. {@code w1:PRIMESUM(1,250) + w2:PRIMESUM(251,500) + ...} */
    public String distributionSummary() {
        return subtasks.stream()
                .map(s -> "w" + s.getWorkerId() + ":" + s.getDescription())
                .collect(Collectors.joining(" + "));
    }

    /** Multi-line human readable report (used by the CLI and the GUI details pane). */
    public String toReport() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Job %s: %s = %s%n", jobId, description, getValueText()));
        sb.append(String.format("  coordinator : Worker %d (term %d, job %d of %d, JAC now %d)%n",
                coordinatorId, term, jobNumberInTerm, maxJobsPerTerm, coordinatorJac));
        sb.append(String.format("  distribution: %s%n", distributionSummary()));
        for (SubtaskReport s : subtasks) {
            sb.append("    ").append(s).append(System.lineSeparator());
        }
        sb.append(String.format("  total time  : %d ms", elapsedMs));
        return sb.toString();
    }
}

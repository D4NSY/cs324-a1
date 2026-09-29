package distrilab.client.gui;

import distrilab.api.model.JobResult;
import distrilab.jobs.Job;

/**
 * One row of the client's job table. Only ever read or modified on the Swing event
 * dispatch thread (EDT), so it needs no synchronisation of its own.
 */
final class JobRecord {

    enum State { QUEUED, RUNNING, DONE, FAILED }

    private final int number;
    private final Job job;
    private final long submittedAt = System.currentTimeMillis();
    private State state = State.QUEUED;
    private String progress = "Queued";
    private JobResult result;
    private String error;
    private long finishedAt;

    JobRecord(int number, Job job) {
        this.number = number;
        this.job = job;
    }

    int getNumber() {
        return number;
    }

    Job getJob() {
        return job;
    }

    State getState() {
        return state;
    }

    String getProgress() {
        return progress;
    }

    JobResult getResult() {
        return result;
    }

    String getError() {
        return error;
    }

    long getElapsedMs() {
        return (finishedAt == 0 ? System.currentTimeMillis() : finishedAt) - submittedAt;
    }

    void running(String message) {
        state = State.RUNNING;
        progress = message;
    }

    void done(JobResult jobResult) {
        state = State.DONE;
        result = jobResult;
        progress = "Done";
        finishedAt = System.currentTimeMillis();
    }

    void failed(String message) {
        state = State.FAILED;
        error = message;
        progress = "Failed";
        finishedAt = System.currentTimeMillis();
    }
}

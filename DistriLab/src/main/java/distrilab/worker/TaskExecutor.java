package distrilab.worker;

import distrilab.api.JobExecutionException;
import distrilab.api.model.TaskResult;
import distrilab.jobs.Job;
import distrilab.util.Concurrency;
import distrilab.util.Formats;
import distrilab.util.Log;
import distrilab.util.NamedThreadFactory;

import java.math.BigDecimal;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs parts of jobs on a fixed pool of worker threads, so one worker executes several
 * sub-tasks (from different jobs, clients or coordinators) at the same time. Extra tasks
 * queue until a thread is free, which bounds CPU use.
 */
public final class TaskExecutor {

    private final int workerId;
    private final int threads;
    private final ExecutorService pool;
    private final AtomicInteger running = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicLong completed = new AtomicLong();

    public TaskExecutor(int workerId, int threads) {
        this.workerId = workerId;
        this.threads = threads;
        this.pool = Executors.newFixedThreadPool(threads, new NamedThreadFactory("task-W" + workerId, true));
    }

    /**
     * Runs the task on the pool and waits for it. The calling thread (an RMI thread or a
     * coordinator dispatch thread) blocks, but the computation happens on a pool thread.
     */
    public TaskResult execute(String taskId, Job task) throws JobExecutionException {
        Future<TaskResult> future = pool.submit(() -> run(taskId, task));
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Log.warn("Task %s failed: %s", taskId, Log.describe(cause));
            throw new JobExecutionException("Task " + taskId + " failed on Worker " + workerId + ": "
                    + Log.describe(cause));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new JobExecutionException("Worker " + workerId + " was interrupted while running " + taskId);
        }
    }

    private TaskResult run(String taskId, Job task) {
        int now = running.incrementAndGet();
        peak.accumulateAndGet(now, Math::max);
        Log.info("Task %s started: %s  (%d task(s) now running on this worker)", taskId, task.describe(), now);
        long startNs = System.nanoTime();
        try {
            BigDecimal value = task.compute();
            long ms = (System.nanoTime() - startNs) / 1_000_000;
            Log.info("Task %s finished: %s = %s in %d ms", taskId, task.describe(), Formats.number(value), ms);
            return new TaskResult(workerId, value, ms, Thread.currentThread().getName());
        } finally {
            running.decrementAndGet();
            completed.incrementAndGet();
        }
    }

    public int getThreads() {
        return threads;
    }

    public int getRunning() {
        return running.get();
    }

    public int getPeak() {
        return peak.get();
    }

    public long getCompleted() {
        return completed.get();
    }

    public void shutdown() {
        Concurrency.shutdown(pool, 2000);
    }
}

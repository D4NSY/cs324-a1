package distrilab.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Small helpers for working with threads and executors. */
public final class Concurrency {

    private Concurrency() {
    }

    /** Orderly executor shutdown: stop accepting work, wait briefly, then interrupt. */
    public static void shutdown(ExecutorService executor, long waitMs) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(waitMs, TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** Sleeps, restoring the interrupt flag instead of swallowing it. Returns false if interrupted. */
    public static boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Sleeps for a random time in [0, maxMillis] - used to de-synchronise competing initiators. */
    public static boolean sleepRandom(long maxMillis) {
        if (maxMillis <= 0) {
            return true;
        }
        return sleep(ThreadLocalRandom.current().nextLong(maxMillis + 1));
    }

    /**
     * Wraps a periodic task so an exception is logged instead of silently cancelling the
     * schedule (a ScheduledExecutorService stops repeating a task that throws).
     */
    public static Runnable guarded(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.error(t, "Periodic task '%s' failed", name);
            }
        };
    }
}

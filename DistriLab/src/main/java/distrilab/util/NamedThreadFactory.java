package distrilab.util;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Gives pool threads readable names (e.g. {@code task-W3-2}) so logs show who did what. */
public final class NamedThreadFactory implements ThreadFactory {

    private final String prefix;
    private final boolean daemon;
    private final AtomicInteger counter = new AtomicInteger();

    public NamedThreadFactory(String prefix, boolean daemon) {
        this.prefix = prefix;
        this.daemon = daemon;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
        thread.setDaemon(daemon);
        thread.setUncaughtExceptionHandler((t, e) -> Log.error(e, "Uncaught exception in %s", t.getName()));
        return thread;
    }
}

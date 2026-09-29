package distrilab.worker;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Remembers message IDs already processed so that a flooded message is handled at most
 * once per worker.
 * <p>
 * {@link #firstTime(String)} uses {@link ConcurrentHashMap#putIfAbsent}, which is atomic:
 * even if the same ELECTION arrives from two neighbours at the same instant on two RMI
 * threads, exactly one thread gets {@code true} and processes it; the other sees the
 * duplicate. (A separate "contains" check followed by "add" would be a race condition.)
 * Old IDs are purged after a time-to-live so memory stays bounded.
 */
public final class DuplicateFilter {

    private final Map<String, Long> seen = new ConcurrentHashMap<>();
    private final long ttlMs;
    private final AtomicLong firstSeen = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();

    public DuplicateFilter(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    /** True exactly once per ID: the first time it is offered. */
    public boolean firstTime(String messageId) {
        boolean first = seen.putIfAbsent(messageId, System.currentTimeMillis()) == null;
        if (first) {
            firstSeen.incrementAndGet();
        } else {
            duplicates.incrementAndGet();
        }
        return first;
    }

    /** Forgets IDs older than the time-to-live. */
    public void purgeExpired() {
        long cutoff = System.currentTimeMillis() - ttlMs;
        seen.entrySet().removeIf(e -> e.getValue() < cutoff);
    }

    public long getProcessedCount() {
        return firstSeen.get();
    }

    public long getDuplicateCount() {
        return duplicates.get();
    }
}

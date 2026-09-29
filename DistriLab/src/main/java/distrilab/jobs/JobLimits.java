package distrilab.jobs;

import distrilab.util.Config;

/** Size limits that protect the cluster from absurd inputs. Values come from configuration. */
public final class JobLimits {

    private final int maxListSize;
    private final long maxPrimeSumRange;
    private final long maxPrimeSumEnd;

    public JobLimits(int maxListSize, long maxPrimeSumRange, long maxPrimeSumEnd) {
        this.maxListSize = maxListSize;
        this.maxPrimeSumRange = maxPrimeSumRange;
        this.maxPrimeSumEnd = Math.min(maxPrimeSumEnd, PrimeMath.MAX_SIEVE_END);
    }

    public static JobLimits from(Config config) {
        return new JobLimits(config.getPositiveInt("jobs.maxListSize"),
                config.getLong("jobs.maxPrimeSumRange"),
                config.getLong("jobs.maxPrimeSumEnd"));
    }

    public int getMaxListSize() {
        return maxListSize;
    }

    public long getMaxPrimeSumRange() {
        return maxPrimeSumRange;
    }

    public long getMaxPrimeSumEnd() {
        return maxPrimeSumEnd;
    }
}

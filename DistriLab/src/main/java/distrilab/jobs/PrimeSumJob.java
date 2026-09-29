package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.math.BigDecimal;
import java.util.List;

/**
 * PRIMESUM(start, end): the sum of all primes in [start, end]. The range is divided
 * into equal-length sub-ranges, e.g. PRIMESUM(1,1000) over 4 workers gives
 * PRIMESUM(1,250), PRIMESUM(251,500), PRIMESUM(501,750), PRIMESUM(751,1000).
 */
public final class PrimeSumJob extends Job {
    private static final long serialVersionUID = 1L;

    private final long start;
    private final long end;

    public PrimeSumJob(long start, long end) {
        if (start > end) {
            throw new IllegalArgumentException("start (" + start + ") must not be greater than end (" + end + ")");
        }
        try {
            Math.addExact(Math.subtractExact(end, start), 1L);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("The range " + start + ".." + end + " is too large");
        }
        this.start = start;
        this.end = end;
    }

    public long getStart() {
        return start;
    }

    public long getEnd() {
        return end;
    }

    @Override
    public JobType getType() {
        return JobType.PRIMESUM;
    }

    @Override
    public long getWorkloadSize() {
        return end - start + 1;
    }

    @Override
    public void validate(JobLimits limits) throws InvalidJobException {
        if (end > limits.getMaxPrimeSumEnd()) {
            throw new InvalidJobException("PRIMESUM end must be at most " + limits.getMaxPrimeSumEnd());
        }
        if (getWorkloadSize() > limits.getMaxPrimeSumRange()) {
            throw new InvalidJobException("PRIMESUM range may cover at most " + limits.getMaxPrimeSumRange()
                    + " numbers (this one covers " + getWorkloadSize() + ")");
        }
    }

    @Override
    protected Job slice(long offset, long length) {
        long from = start + offset;
        return new PrimeSumJob(from, from + length - 1);
    }

    @Override
    public BigDecimal compute() {
        return new BigDecimal(PrimeMath.sumPrimes(start, end));
    }

    @Override
    public BigDecimal combine(List<BigDecimal> partialResults) {
        requireResults(partialResults);
        return partialResults.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Override
    public String describe() {
        return "PRIMESUM(" + start + "," + end + ")";
    }
}

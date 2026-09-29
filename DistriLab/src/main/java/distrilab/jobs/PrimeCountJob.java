package distrilab.jobs;

import java.math.BigDecimal;
import java.util.List;

/** PRIMECOUNT(numbers): how many values in an unsorted list are prime. */
public final class PrimeCountJob extends NumberListJob {
    private static final long serialVersionUID = 1L;

    public PrimeCountJob(NumberList numbers) {
        super(numbers, 1, numbers.size());
    }

    private PrimeCountJob(NumberList part, long firstItem, long totalItems) {
        super(part, firstItem, totalItems);
    }

    @Override
    public JobType getType() {
        return JobType.PRIMECOUNT;
    }

    @Override
    protected NumberListJob createPart(NumberList part, long firstItemOfPart) {
        return new PrimeCountJob(part, firstItemOfPart, getTotalItems());
    }

    @Override
    public BigDecimal compute() {
        return BigDecimal.valueOf(getNumbers().countPrimes());
    }

    @Override
    public BigDecimal combine(List<BigDecimal> partialResults) {
        requireResults(partialResults);
        return partialResults.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

package distrilab.jobs;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/** MAX(numbers): the largest value in an unsorted list. */
public final class MaxJob extends NumberListJob {
    private static final long serialVersionUID = 1L;

    public MaxJob(NumberList numbers) {
        super(numbers, 1, numbers.size());
    }

    private MaxJob(NumberList part, long firstItem, long totalItems) {
        super(part, firstItem, totalItems);
    }

    @Override
    public JobType getType() {
        return JobType.MAX;
    }

    @Override
    protected NumberListJob createPart(NumberList part, long firstItemOfPart) {
        return new MaxJob(part, firstItemOfPart, getTotalItems());
    }

    @Override
    public BigDecimal compute() {
        return getNumbers().max();
    }

    @Override
    public BigDecimal combine(List<BigDecimal> partialResults) {
        requireResults(partialResults);
        return Collections.max(partialResults);
    }
}

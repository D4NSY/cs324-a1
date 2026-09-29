package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.util.List;

/**
 * Common base for jobs whose input is a list of numbers (MAX and PRIMECOUNT).
 * Implements slicing once; subclasses provide the computation and the combination.
 */
public abstract class NumberListJob extends Job {
    private static final long serialVersionUID = 1L;

    private final NumberList numbers;
    /** 1-based position of this slice's first item in the client's original list. */
    private final long firstItem;
    /** Size of the client's original list. */
    private final long totalItems;

    protected NumberListJob(NumberList numbers, long firstItem, long totalItems) {
        if (numbers == null || numbers.size() == 0) {
            throw new IllegalArgumentException("A number-list job needs at least one number");
        }
        this.numbers = numbers;
        this.firstItem = firstItem;
        this.totalItems = totalItems;
    }

    protected final NumberList getNumbers() {
        return numbers;
    }

    protected final long getTotalItems() {
        return totalItems;
    }

    /** Factory method: a job of the same concrete type over a slice of the list. */
    protected abstract NumberListJob createPart(NumberList part, long firstItemOfPart);

    @Override
    public long getWorkloadSize() {
        return numbers.size();
    }

    @Override
    protected final Job slice(long offset, long length) {
        NumberList part = numbers.slice((int) offset, (int) (offset + length));
        return createPart(part, firstItem + offset);
    }

    @Override
    public void validate(JobLimits limits) throws InvalidJobException {
        if (numbers.size() > limits.getMaxListSize()) {
            throw new InvalidJobException("The list has " + numbers.size() + " numbers; the configured limit is "
                    + limits.getMaxListSize());
        }
    }

    @Override
    public String describe() {
        if (numbers.size() == totalItems && firstItem == 1) {
            List<String> preview = numbers.preview(4);
            String shown = String.join(", ", preview) + (numbers.size() > preview.size() ? ", ..." : "");
            return getType().name() + "(" + numbers.size() + " numbers: " + shown + ")";
        }
        long last = firstItem + numbers.size() - 1;
        return getType().name() + "(items " + firstItem + "-" + last + ")";
    }
}

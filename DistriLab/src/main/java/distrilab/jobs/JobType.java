package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The computations the cluster supports. */
public enum JobType {
    MAX("MAX(numbers)", "Largest value in an unsorted list of numbers", false),
    PRIMESUM("PRIMESUM(start, end)", "Sum of all prime numbers in the range [start, end]", true),
    PRIMECOUNT("PRIMECOUNT(numbers)", "How many values in an unsorted list are prime", false);

    private final String signature;
    private final String description;
    private final boolean rangeInput;

    JobType(String signature, String description, boolean rangeInput) {
        this.signature = signature;
        this.description = description;
        this.rangeInput = rangeInput;
    }

    public String getSignature() {
        return signature;
    }

    public String getDescription() {
        return description;
    }

    /** True if the job takes (start, end) rather than a list of numbers. */
    public boolean takesRange() {
        return rangeInput;
    }

    /** Case-insensitive parse with a helpful error message. */
    public static JobType parse(String text) throws InvalidJobException {
        String cleaned = text == null ? "" : text.trim().toUpperCase().replace("_", "").replace(" ", "");
        for (JobType type : values()) {
            if (type.name().equals(cleaned)) {
                return type;
            }
        }
        throw new InvalidJobException("Unknown job type '" + text + "'. Expected one of "
                + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")));
    }

    @Override
    public String toString() {
        return signature;
    }
}

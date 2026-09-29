package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.util.List;

/** Builds validated {@link Job} objects from user input (GUI fields, CLI arguments, CSV rows). */
public final class JobFactory {

    private JobFactory() {
    }

    public static Job create(JobType type, List<String> arguments) throws InvalidJobException {
        switch (type) {
            case MAX:
                return new MaxJob(NumberList.parse(arguments));
            case PRIMECOUNT:
                return new PrimeCountJob(NumberList.parse(arguments));
            case PRIMESUM:
                return primeSum(arguments);
            default:
                throw new InvalidJobException("Unsupported job type " + type);
        }
    }

    public static Job fromText(JobType type, String text) throws InvalidJobException {
        return create(type, InputParser.tokenize(text));
    }

    public static Job primeSum(List<String> arguments) throws InvalidJobException {
        if (arguments.size() != 2) {
            throw new InvalidJobException("PRIMESUM needs exactly two numbers: start and end (got "
                    + arguments.size() + ")");
        }
        long start = parseWhole(arguments.get(0), "start");
        long end = parseWhole(arguments.get(1), "end");
        if (start > end) {
            throw new InvalidJobException("PRIMESUM start (" + start + ") must not be greater than end (" + end + ")");
        }
        try {
            return new PrimeSumJob(start, end);
        } catch (IllegalArgumentException e) {
            throw new InvalidJobException(e.getMessage());
        }
    }

    private static long parseWhole(String token, String name) throws InvalidJobException {
        try {
            return new java.math.BigDecimal(token.trim()).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new InvalidJobException("PRIMESUM " + name + " must be a whole number, got '" + token + "'");
        }
    }
}

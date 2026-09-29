package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable list of numbers stored compactly and exactly: every value is kept as a
 * {@code long} "unscaled" integer with one shared decimal scale. For example
 * {@code [3.5, 2, 10.25]} is stored as {@code [350, 200, 1025]} with scale 2.
 * This is exact (no floating-point rounding), supports decimals for MAX, and is cheap
 * to send over RMI (a primitive array rather than millions of objects).
 */
public final class NumberList implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Maximum number of decimal places accepted. */
    public static final int MAX_SCALE = 9;

    private final long[] unscaled;
    private final int scale;

    private NumberList(long[] unscaled, int scale) {
        this.unscaled = unscaled;
        this.scale = scale;
    }

    /** Parses number tokens such as "42", "-7", "3.25", "1e3". */
    public static NumberList parse(List<String> tokens) throws InvalidJobException {
        if (tokens == null || tokens.isEmpty()) {
            throw new InvalidJobException("The list of numbers is empty");
        }
        BigDecimal[] values = new BigDecimal[tokens.size()];
        int maxScale = 0;
        for (int i = 0; i < values.length; i++) {
            String token = tokens.get(i).trim();
            try {
                BigDecimal v = new BigDecimal(token).stripTrailingZeros();
                values[i] = v;
                maxScale = Math.max(maxScale, Math.max(0, v.scale()));
            } catch (NumberFormatException e) {
                throw new InvalidJobException("'" + token + "' (item " + (i + 1) + ") is not a number");
            }
        }
        if (maxScale > MAX_SCALE) {
            throw new InvalidJobException("Numbers may have at most " + MAX_SCALE + " decimal places");
        }
        long[] unscaled = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            try {
                unscaled[i] = values[i].setScale(maxScale, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
            } catch (ArithmeticException e) {
                throw new InvalidJobException("'" + tokens.get(i).trim() + "' (item " + (i + 1)
                        + ") is too large to process exactly");
            }
        }
        return new NumberList(unscaled, maxScale);
    }

    /** Convenience factory for whole numbers. */
    public static NumberList ofLongs(long... values) {
        if (values.length == 0) {
            throw new IllegalArgumentException("empty list");
        }
        return new NumberList(values.clone(), 0);
    }

    public int size() {
        return unscaled.length;
    }

    /** Copy of items [from, to). Copying (not sharing) keeps a slice small when serialised. */
    public NumberList slice(int from, int to) {
        return new NumberList(Arrays.copyOfRange(unscaled, from, to), scale);
    }

    public BigDecimal get(int index) {
        return BigDecimal.valueOf(unscaled[index], scale);
    }

    public BigDecimal max() {
        long best = Long.MIN_VALUE;
        for (long v : unscaled) {
            if (v > best) {
                best = v;
            }
        }
        return BigDecimal.valueOf(best, scale);
    }

    /** Counts the values that are prime (a prime must be a whole number >= 2). */
    public long countPrimes() {
        long factor = 1;
        for (int i = 0; i < scale; i++) {
            factor *= 10;
        }
        long count = 0;
        for (long v : unscaled) {
            if (v % factor == 0 && PrimeMath.isPrime(v / factor)) {
                count++;
            }
        }
        return count;
    }

    /** The first few values, for descriptions. */
    public List<String> preview(int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, unscaled.length); i++) {
            out.add(get(i).stripTrailingZeros().toPlainString());
        }
        return out;
    }
}

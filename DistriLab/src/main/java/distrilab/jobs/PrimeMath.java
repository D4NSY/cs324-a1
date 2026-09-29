package distrilab.jobs;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Prime-number routines used by the PRIMESUM and PRIMECOUNT jobs.
 * <ul>
 *   <li>{@link #isPrime(long)} - deterministic Miller-Rabin, correct for every 64-bit value.</li>
 *   <li>{@link #sumPrimes(long, long)} - segmented Sieve of Eratosthenes over a range.</li>
 * </ul>
 * All methods are stateless and therefore safe to call from many threads at once.
 */
public final class PrimeMath {

    /** Largest range end supported by the sieve (its base primes go up to 10^7). */
    public static final long MAX_SIEVE_END = 100_000_000_000_000L;

    private static final long[] BASES_ALL = {2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37};
    private static final long[] BASES_SMALL = {2, 3, 5, 7};
    /** Bases 2,3,5,7 are sufficient below this bound (Jaeschke 1993). */
    private static final long SMALL_BASES_LIMIT = 3_215_031_751L;
    /** floor(sqrt(Long.MAX_VALUE)): below this a*b cannot overflow for a,b < m. */
    private static final long MUL_SAFE_LIMIT = 3_037_000_499L;
    private static final int SEGMENT_SIZE = 1 << 16;

    private PrimeMath() {
    }

    public static boolean isPrime(long n) {
        if (n < 2) {
            return false;
        }
        for (long p : BASES_ALL) {
            if (n % p == 0) {
                return n == p;
            }
        }
        if (n < 37L * 37L) {
            return true; // no prime factor <= 37 and n < 37^2
        }
        long d = n - 1;
        int r = Long.numberOfTrailingZeros(d);
        d >>= r;
        long[] bases = n < SMALL_BASES_LIMIT ? BASES_SMALL : BASES_ALL;
        for (long a : bases) {
            if (!millerRabinRound(n, a, d, r)) {
                return false;
            }
        }
        return true;
    }

    private static boolean millerRabinRound(long n, long a, long d, int r) {
        long x = powMod(a, d, n);
        if (x == 1 || x == n - 1) {
            return true;
        }
        for (int i = 1; i < r; i++) {
            x = mulMod(x, x, n);
            if (x == n - 1) {
                return true;
            }
            if (x == 1) {
                return false;
            }
        }
        return false;
    }

    private static long mulMod(long a, long b, long m) {
        if (m <= MUL_SAFE_LIMIT) {
            return (a * b) % m;
        }
        return BigInteger.valueOf(a).multiply(BigInteger.valueOf(b)).mod(BigInteger.valueOf(m)).longValue();
    }

    private static long powMod(long base, long exponent, long m) {
        long result = 1;
        long b = base % m;
        long e = exponent;
        while (e > 0) {
            if ((e & 1) == 1) {
                result = mulMod(result, b, m);
            }
            b = mulMod(b, b, m);
            e >>= 1;
        }
        return result;
    }

    /** Sum of all primes p with lo <= p <= hi (0 if there are none). */
    public static BigInteger sumPrimes(long lo, long hi) {
        if (hi > MAX_SIEVE_END) {
            throw new IllegalArgumentException("Range end " + hi + " exceeds the supported maximum " + MAX_SIEVE_END);
        }
        if (hi < 2 || lo > hi) {
            return BigInteger.ZERO;
        }
        long start = Math.max(lo, 2);
        int[] basePrimes = primesUpTo(isqrt(hi));
        boolean[] composite = new boolean[SEGMENT_SIZE];
        BigInteger total = BigInteger.ZERO;
        long flushThreshold = Long.MAX_VALUE - hi;

        for (long segStart = start; segStart <= hi; segStart += SEGMENT_SIZE) {
            long segEnd = Math.min(hi, segStart + SEGMENT_SIZE - 1);
            int length = (int) (segEnd - segStart + 1);
            Arrays.fill(composite, 0, length, false);
            for (int p : basePrimes) {
                long square = (long) p * p;
                if (square > segEnd) {
                    break;
                }
                long first = Math.max(square, ((segStart + p - 1) / p) * p);
                for (long multiple = first; multiple <= segEnd; multiple += p) {
                    composite[(int) (multiple - segStart)] = true;
                }
            }
            long segmentSum = 0;
            for (int i = 0; i < length; i++) {
                if (!composite[i]) {
                    segmentSum += segStart + i;
                    if (segmentSum > flushThreshold) {
                        total = total.add(BigInteger.valueOf(segmentSum));
                        segmentSum = 0;
                    }
                }
            }
            total = total.add(BigInteger.valueOf(segmentSum));
        }
        return total;
    }

    /** Simple sieve returning all primes <= n. */
    static int[] primesUpTo(int n) {
        if (n < 2) {
            return new int[0];
        }
        boolean[] composite = new boolean[n + 1];
        int count = 0;
        for (int i = 2; i <= n; i++) {
            if (!composite[i]) {
                count++;
                for (long j = (long) i * i; j <= n; j += i) {
                    composite[(int) j] = true;
                }
            }
        }
        int[] primes = new int[count];
        int k = 0;
        for (int i = 2; i <= n; i++) {
            if (!composite[i]) {
                primes[k++] = i;
            }
        }
        return primes;
    }

    static int isqrt(long n) {
        long r = (long) Math.sqrt((double) n);
        while (r * r > n) {
            r--;
        }
        while ((r + 1) * (r + 1) <= n) {
            r++;
        }
        return (int) r;
    }
}

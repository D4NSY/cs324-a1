package distrilab.selftest;

import distrilab.api.InvalidJobException;
import distrilab.api.WorkerService;
import distrilab.api.model.Candidate;
import distrilab.api.model.WorkerRef;
import distrilab.jobs.CsvLoader;
import distrilab.jobs.Job;
import distrilab.jobs.JobFactory;
import distrilab.jobs.JobType;
import distrilab.jobs.MaxJob;
import distrilab.jobs.NumberList;
import distrilab.jobs.PrimeCountJob;
import distrilab.jobs.PrimeMath;
import distrilab.jobs.PrimeSumJob;
import distrilab.util.Config;
import distrilab.util.ConfigException;
import distrilab.worker.DuplicateFilter;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Dependency-free unit tests (no JUnit needed). Run with {@code java -jar distrilab.jar test}.
 * Each test is a method; the tiny harness counts passes and failures.
 */
public final class UnitTests {

    private int passed;
    private final List<String> failures = new ArrayList<>();

    public static void main(String[] args) {
        UnitTests t = new UnitTests();
        t.run("PRIMESUM(1,1000) over 4 workers splits exactly like the brief", t::primeSumSplitMatchesBrief);
        t.run("Uneven splits differ by at most one unit", t::unevenSplit);
        t.run("More workers than list items -> no empty parts", t::moreWorkersThanItems);
        t.run("Split + compute + combine equals sequential result", t::distributedEqualsSequential);
        t.run("Miller-Rabin agrees with trial division up to 50,000", t::isPrimeSmall);
        t.run("Miller-Rabin on large primes and strong pseudoprimes", t::isPrimeLarge);
        t.run("Segmented sieve sums (known values)", t::sumPrimesKnownValues);
        t.run("MAX handles decimals and negatives exactly", t::maxWithDecimals);
        t.run("PRIMECOUNT counts whole-number primes only", t::primeCount);
        t.run("Election rule: lowest JAC wins, tie -> highest ID", t::candidateOrdering);
        t.run("Duplicate filter admits an ID exactly once under contention", t::duplicateFilterConcurrent);
        t.run("Invalid input gives clear InvalidJobException", t::invalidInput);
        t.run("CSV loader reads numbers and batch files", t::csvLoader);
        t.run("Config aliases and unknown-key rejection", t::configHandling);

        System.out.println();
        System.out.printf("Unit tests: %d passed, %d failed%n", t.passed, t.failures.size());
        t.failures.forEach(f -> System.out.println("  FAILED: " + f));
        System.exit(t.failures.isEmpty() ? 0 : 1);
    }

    // ------------------------------------------------------------------ harness

    @FunctionalInterface
    private interface TestBody {
        void run() throws Exception;
    }

    private void run(String name, TestBody body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable e) {
            failures.add(name + " -> " + e);
            System.out.println("FAIL  " + name + " -> " + e);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void equal(Object expected, Object actual, String what) {
        if (!expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    // ------------------------------------------------------------------ tests

    private void primeSumSplitMatchesBrief() {
        List<String> parts = new PrimeSumJob(1, 1000).split(4).stream().map(Job::describe).collect(Collectors.toList());
        equal(Arrays.asList("PRIMESUM(1,250)", "PRIMESUM(251,500)", "PRIMESUM(501,750)", "PRIMESUM(751,1000)"),
                parts, "split");
    }

    private void unevenSplit() {
        List<Job> parts = new PrimeSumJob(1, 10).split(3);
        equal(Arrays.asList("PRIMESUM(1,4)", "PRIMESUM(5,7)", "PRIMESUM(8,10)"),
                parts.stream().map(Job::describe).collect(Collectors.toList()), "split of 10 into 3");
        List<Job> listParts = new MaxJob(NumberList.ofLongs(1, 2, 3, 4, 5, 6, 7)).split(3);
        equal(Arrays.asList(3L, 2L, 2L), listParts.stream().map(Job::getWorkloadSize).collect(Collectors.toList()),
                "list part sizes");
        equal("MAX(items 4-5)", listParts.get(1).describe(), "slice description");
    }

    private void moreWorkersThanItems() {
        List<Job> parts = new MaxJob(NumberList.ofLongs(5, 9, 1)).split(5);
        equal(3, parts.size(), "number of parts");
        List<Job> single = new PrimeSumJob(7, 7).split(4);
        equal(1, single.size(), "single-number range");
    }

    private void distributedEqualsSequential() {
        PrimeSumJob job = new PrimeSumJob(1, 1_000_000);
        List<BigDecimal> partials = job.split(7).stream().map(Job::compute).collect(Collectors.toList());
        equal(new BigDecimal("37550402023"), job.combine(partials), "sum of primes below one million");

        long[] values = new long[10_001];
        for (int i = 0; i < values.length; i++) {
            values[i] = (i * 7919L) % 10_007; // a scrambled permutation-like sequence
        }
        MaxJob max = new MaxJob(NumberList.ofLongs(values));
        List<BigDecimal> maxParts = max.split(6).stream().map(Job::compute).collect(Collectors.toList());
        equal(max.compute(), max.combine(maxParts), "distributed MAX");

        PrimeCountJob count = new PrimeCountJob(NumberList.ofLongs(values));
        List<BigDecimal> countParts = count.split(4).stream().map(Job::compute).collect(Collectors.toList());
        equal(count.compute(), count.combine(countParts), "distributed PRIMECOUNT");
    }

    private void isPrimeSmall() {
        for (long n = -5; n <= 50_000; n++) {
            boolean naive = n >= 2;
            for (long d = 2; d * d <= n && naive; d++) {
                naive = n % d != 0;
            }
            if (naive != PrimeMath.isPrime(n)) {
                throw new AssertionError("isPrime(" + n + ") should be " + naive);
            }
        }
    }

    private void isPrimeLarge() {
        check(PrimeMath.isPrime(2_147_483_647L), "2^31-1 is prime");
        check(PrimeMath.isPrime(999_999_999_989L), "largest 12-digit prime");
        check(PrimeMath.isPrime(2_305_843_009_213_693_951L), "2^61-1 is prime");
        check(!PrimeMath.isPrime(3_215_031_751L), "strong pseudoprime to bases 2,3,5,7 is composite");
        check(!PrimeMath.isPrime(561L), "Carmichael number 561 is composite");
        check(!PrimeMath.isPrime(2_305_843_009_213_693_953L), "2^61+1 is composite");
    }

    private void sumPrimesKnownValues() {
        equal(BigInteger.valueOf(76127), PrimeMath.sumPrimes(1, 1000), "primes <= 1000");
        equal(BigInteger.valueOf(142_913_828_922L), PrimeMath.sumPrimes(1, 2_000_000), "primes < 2 million");
        equal(BigInteger.valueOf(17), PrimeMath.sumPrimes(-10, 10), "negative start");
        equal(BigInteger.ZERO, PrimeMath.sumPrimes(24, 28), "range without primes");
        equal(BigInteger.valueOf(999_999_999_989L), PrimeMath.sumPrimes(999_999_999_980L, 999_999_999_999L),
                "single large prime in range");
    }

    private void maxWithDecimals() throws InvalidJobException {
        Job job = JobFactory.fromText(JobType.MAX, "3.5, 2, 10.25, -4, 10.2");
        equal(new BigDecimal("10.25"), job.compute(), "max");
        Job negatives = JobFactory.fromText(JobType.MAX, "-7 -3 -12");
        equal(0, new BigDecimal("-3").compareTo(negatives.compute()), "max of negatives");
    }

    private void primeCount() throws InvalidJobException {
        Job job = JobFactory.fromText(JobType.PRIMECOUNT, "2, 3, 4, 5.0, 7.5, -7, 1, 97, 97");
        equal(0, BigDecimal.valueOf(5).compareTo(job.compute()), "2,3,5.0,97,97 are prime");
    }

    private void candidateOrdering() {
        WorkerRef dummy = dummyRef(1);
        Candidate lowJac = new Candidate(3, 0, dummy);
        Candidate highJac = new Candidate(9, 1, dummy);
        check(lowJac.beats(highJac), "lower JAC must win even with a lower ID");
        check(!highJac.beats(lowJac), "higher JAC must lose");
        Candidate id5 = new Candidate(5, 2, dummy);
        Candidate id4 = new Candidate(4, 2, dummy);
        check(id5.beats(id4) && !id4.beats(id5), "equal JAC -> highest ID wins");
        equal(5, Candidate.best(id4, id5).getWorkerId(), "best()");
        check(!id5.beats(new Candidate(5, 2, dummy)), "a candidate does not beat itself");
    }

    private void duplicateFilterConcurrent() throws InterruptedException {
        DuplicateFilter filter = new DuplicateFilter(60_000);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger firsts = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    go.await();
                    if (filter.firstTime("E1-1-abc")) {
                        firsts.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        go.countDown();
        pool.shutdown();
        check(pool.awaitTermination(5, TimeUnit.SECONDS), "threads finished");
        equal(1, firsts.get(), "number of threads that processed the message");
        equal(15L, filter.getDuplicateCount(), "duplicates counted");
    }

    private void invalidInput() {
        expectInvalid(() -> JobFactory.fromText(JobType.MAX, "4, eight, 15"), "eight");
        expectInvalid(() -> JobFactory.fromText(JobType.PRIMESUM, "100 1"), "greater than end");
        expectInvalid(() -> JobFactory.fromText(JobType.PRIMESUM, "1"), "exactly two");
        expectInvalid(() -> JobFactory.fromText(JobType.PRIMECOUNT, ""), "empty");
        expectInvalid(() -> JobType.parse("MIN"), "Unknown job type");
    }

    private interface JobSupplier {
        Object get() throws InvalidJobException;
    }

    private static void expectInvalid(JobSupplier supplier, String fragment) {
        try {
            supplier.get();
        } catch (InvalidJobException e) {
            check(e.getMessage().contains(fragment), "message '" + e.getMessage() + "' should mention '" + fragment + "'");
            return;
        }
        throw new AssertionError("expected InvalidJobException mentioning '" + fragment + "'");
    }

    private void csvLoader() throws IOException, InvalidJobException {
        Path numbers = Files.createTempFile("numbers", ".csv");
        Path batch = Files.createTempFile("jobs", ".csv");
        try {
            Files.write(numbers, Arrays.asList("value,note", "4,x", "8", "15;16", "\"23\",42"), StandardCharsets.UTF_8);
            CsvLoader.NumberData data = CsvLoader.readNumbers(numbers);
            equal(Arrays.asList("4", "8", "15", "16", "23", "42"), data.getTokens(), "numbers");
            equal(3, data.getSkippedCells(), "skipped cells");

            Files.write(batch, Arrays.asList("type,args", "# comment", "PRIMESUM,1,1000", "max,4,8,15", "",
                    "PRIMECOUNT,2,3,4"), StandardCharsets.UTF_8);
            List<Job> jobs = CsvLoader.readBatch(batch);
            equal(Arrays.asList(JobType.PRIMESUM, JobType.MAX, JobType.PRIMECOUNT),
                    jobs.stream().map(Job::getType).collect(Collectors.toList()), "batch job types");
        } finally {
            Files.deleteIfExists(numbers);
            Files.deleteIfExists(batch);
        }
    }

    private void configHandling() {
        Config c = Config.load(new String[]{"--id=7", "--bootstrap=10.0.0.5:2000", "--worker.threads=6", "extra"},
                Config.Role.WORKER);
        equal(7, c.getInt("worker.id"), "--id alias");
        equal("10.0.0.5", c.get("bootstrap.host"), "--bootstrap host");
        equal(2000, c.getInt("bootstrap.port"), "--bootstrap port");
        equal(6, c.getInt("worker.threads"), "explicit key");
        equal(Arrays.asList("extra"), c.getPositional(), "positional");
        try {
            Config.load(new String[]{"--wroker.threads=2"}, Config.Role.WORKER);
            throw new AssertionError("misspelt key should be rejected");
        } catch (ConfigException expected) {
            check(expected.getMessage().contains("Unknown option"), "helpful message");
        }
    }

    /** A WorkerRef backed by a do-nothing proxy - enough for comparisons, never called. */
    private static WorkerRef dummyRef(int id) {
        WorkerService stub = (WorkerService) Proxy.newProxyInstance(UnitTests.class.getClassLoader(),
                new Class<?>[]{WorkerService.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException("dummy");
                });
        return new WorkerRef(id, "test", stub);
    }
}

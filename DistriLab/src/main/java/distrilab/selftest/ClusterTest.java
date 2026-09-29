package distrilab.selftest;

import distrilab.api.model.JobResult;
import distrilab.api.model.WorkerRef;
import distrilab.api.model.WorkerStatus;
import distrilab.client.ClusterClient;
import distrilab.client.NetworkReport;
import distrilab.jobs.Job;
import distrilab.jobs.JobFactory;
import distrilab.jobs.JobType;
import distrilab.jobs.PrimeSumJob;
import distrilab.net.BootstrapClient;
import distrilab.net.RmiSupport;
import distrilab.util.Concurrency;
import distrilab.util.Config;
import distrilab.util.Log;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

/**
 * End-to-end test: starts a real bootstrap node and five worker processes on this machine,
 * then checks the behaviour the brief asks for - joining, election rule, 5-job terms,
 * the PRIMESUM split, concurrency, duplicate suppression, coordinator failure and running
 * without the bootstrap node. Run with {@code java -jar distrilab.jar cluster-test}.
 * Each process's log is written to {@code logs/cluster-test/}.
 */
public final class ClusterTest {

    private static final int WORKERS = 5;
    private static final String DEFAULT_PORT = "1299";

    private final String port;
    private final Path logDir = Paths.get("logs", "cluster-test");
    private final Map<Integer, Process> workers = new TreeMap<>();
    private final List<Process> others = new ArrayList<>();
    private Process bootstrap;
    private ClusterClient client;
    private ClusterClient secondClient;
    private BootstrapClient bootstrapClient;
    private List<WorkerRef> knownWorkers = new ArrayList<>();

    private int passed;
    private final List<String> failures = new ArrayList<>();

    private ClusterTest(String port) {
        this.port = port;
    }

    public static void main(String[] args) {
        Log.setNode("ClusterTest");
        String port = DEFAULT_PORT;
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = a.substring("--port=".length());
            }
        }
        ClusterTest test = new ClusterTest(port);
        int exit;
        try {
            exit = test.runAll();
        } catch (Exception e) {
            Log.error(e, "Cluster test aborted");
            exit = 1;
        } finally {
            test.stopAll();
        }
        System.exit(exit);
    }

    private int runAll() throws Exception {
        Files.createDirectories(logDir);
        Config config = Config.defaults().with(map("bootstrap.port", port, "client.retryDelayMs", "300"));
        RmiSupport.configure(config);
        client = ClusterClient.fromConfig(config);
        secondClient = ClusterClient.fromConfig(config);
        bootstrapClient = BootstrapClient.fromConfig(config);

        say("Starting bootstrap node on port " + port + " (logs in " + logDir.toAbsolutePath() + ")");
        bootstrap = spawn("bootstrap", "bootstrap", "--port=" + port, "--bootstrap.leaseTimeoutMs=5000");
        waitFor("bootstrap node", 15_000, () -> {
            try {
                bootstrapClient.activeWorkers();
                return true;
            } catch (RemoteException e) {
                return false;
            }
        });
        for (int id = 1; id <= WORKERS; id++) {
            final int expected = id;
            workers.put(id, spawn("worker-" + id, "worker", "--id=" + id, "--bootstrap=127.0.0.1:" + port,
                    "--worker.console=false", "--worker.heartbeatIntervalMs=1000",
                    "--worker.leaseRenewIntervalMs=1500", "--election.jitterMaxMs=300"));
            waitFor("Worker " + id + " to register", 15_000, () -> activeCount() >= expected);
        }
        waitFor("agreement on a coordinator", 15_000, () -> agreedCoordinator() > 0);
        knownWorkers = bootstrapClient.activeWorkers();

        check("5 workers registered; overlay connected; each worker has at least one neighbour", () -> {
            List<WorkerStatus> s = client.networkStatus();
            say(NetworkReport.format(s));
            require(s.size() == WORKERS, "expected 5 workers, got " + s.size());
            require(NetworkReport.isConnected(s), "overlay is not connected");
            require(s.stream().allMatch(w -> !w.getNeighbours().isEmpty()), "a worker has no neighbours");
        });

        check("Worker 1 (first to join, alone) coordinates term 1 and all workers agree", () -> {
            require(agreedCoordinator() == 1, "expected Worker 1, got " + agreedCoordinator());
        });

        check("PRIMESUM(1,1000) = 76127, split into 5 equal ranges over workers 1-5", () -> {
            JobResult r = client.submit(new PrimeSumJob(1, 1000), ClusterClient.Progress.NONE);
            say(r.toReport());
            requireValue("76127", r);
            require(r.distributionSummary().equals("w1:PRIMESUM(1,200) + w2:PRIMESUM(201,400) + "
                    + "w3:PRIMESUM(401,600) + w4:PRIMESUM(601,800) + w5:PRIMESUM(801,1000)"),
                    "unexpected split: " + r.distributionSummary());
            require(r.getTerm() == 1 && r.getJobNumberInTerm() == 1, "should be job 1 of term 1");
        });

        check("Jobs 2-5 of term 1 (MAX, PRIMECOUNT) give correct results", () -> {
            requireValue("99", client.submit(job(JobType.MAX, "17, 99, -3, 42.5, 8"), ClusterClient.Progress.NONE));
            requireValue("4", client.submit(job(JobType.PRIMECOUNT, "2 4 7 9 11 12 13"), ClusterClient.Progress.NONE));
            requireValue("42.5", client.submit(job(JobType.MAX, "1.5, 42.5, 3"), ClusterClient.Progress.NONE));
            JobResult fifth = client.submit(new PrimeSumJob(1, 100), ClusterClient.Progress.NONE);
            requireValue("1060", fifth);
            require(fifth.getTerm() == 1 && fifth.getJobNumberInTerm() == 5, "5th job should close term 1");
        });

        check("After 5 jobs a new election runs: Worker 5 wins (all others JAC 0 -> highest ID); Worker 1 JAC = 5", () -> {
            waitFor("term 2 coordinator", 15_000, () -> agreedTerm() == 2);
            require(agreedCoordinator() == 5, "expected Worker 5, got " + agreedCoordinator());
            require(jacOf(1) == 5, "Worker 1 JAC should be 5, was " + jacOf(1));
        });

        check("10 concurrent jobs from two clients: all correct, <= 5 jobs per term, one coordinator per term", () -> {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            List<Future<JobResult>> futures = new ArrayList<>();
            BigDecimal expected = new PrimeSumJob(1, 3_000_000).compute();
            for (int i = 0; i < 10; i++) {
                ClusterClient c = i % 2 == 0 ? client : secondClient;
                futures.add(pool.submit(() -> c.submit(new PrimeSumJob(1, 3_000_000), ClusterClient.Progress.NONE)));
            }
            Map<Integer, Set<Integer>> coordinatorsPerTerm = new TreeMap<>();
            Map<Integer, Integer> jobsPerTerm = new TreeMap<>();
            for (Future<JobResult> f : futures) {
                JobResult r = f.get(120, TimeUnit.SECONDS);
                require(r.getValue().compareTo(expected) == 0, "wrong PRIMESUM result " + r.getValueText());
                coordinatorsPerTerm.computeIfAbsent(r.getTerm(), t -> new HashSet<>()).add(r.getCoordinatorId());
                jobsPerTerm.merge(r.getTerm(), 1, Integer::sum);
            }
            pool.shutdown();
            say("Jobs per term: " + jobsPerTerm + "  coordinators per term: " + coordinatorsPerTerm);
            require(jobsPerTerm.values().stream().allMatch(n -> n <= 5), "a term assigned more than 5 jobs");
            require(coordinatorsPerTerm.values().stream().allMatch(s -> s.size() == 1), "a term had 2 coordinators");
            require(coordinatorsPerTerm.equals(expectTerms()), "expected terms 2->W5 and 3->W4, got " + coordinatorsPerTerm);
        });

        check("Leadership rotates by the JAC rule: Worker 3 coordinates term 4", () -> {
            waitFor("term 4 coordinator", 15_000, () -> agreedTerm() == 4);
            require(agreedCoordinator() == 3, "expected Worker 3, got " + agreedCoordinator());
        });

        check("Two separate client processes submit jobs at the same time", () -> {
            Process a = spawn("cli-a", "cli", "repeat", "2", "MAX", "5,3,9", "--bootstrap=127.0.0.1:" + port);
            Process b = spawn("cli-b", "cli", "repeat", "2", "PRIMESUM", "1", "5000", "--bootstrap=127.0.0.1:" + port);
            require(a.waitFor(90, TimeUnit.SECONDS) && a.exitValue() == 0, "client process A failed (see cli-a.log)");
            require(b.waitFor(90, TimeUnit.SECONDS) && b.exitValue() == 0, "client process B failed (see cli-b.log)");
        });

        check("Workers executed several tasks concurrently (thread pools)", () -> {
            int peak = client.networkStatus().stream().mapToInt(WorkerStatus::getPeakConcurrentTasks).max().orElse(0);
            say("Highest number of tasks running at once on one worker: " + peak);
            require(peak >= 2, "no worker ever ran two tasks at once");
        });

        check("Duplicate ELECTION messages (cycles in the overlay) were detected and ignored", () -> {
            List<WorkerStatus> s = client.networkStatus();
            long links = s.stream().mapToLong(w -> w.getNeighbours().size()).sum() / 2;
            long duplicates = s.stream().mapToLong(WorkerStatus::getDuplicatesIgnored).sum();
            say("Overlay links: " + links + " for " + s.size() + " workers; duplicate ELECTION messages ignored: "
                    + duplicates);
            require(links < s.size() || duplicates > 0, "overlay has cycles but no duplicates were recorded");
        });

        check("Coordinator crash: a new coordinator is elected and the next job still completes", () -> {
            int victim = agreedCoordinator();
            say("Killing coordinator Worker " + victim);
            workers.remove(victim).destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            JobResult r = client.submit(job(JobType.PRIMECOUNT, "2 3 5 7 11 13 17 19 23 29"), ClusterClient.Progress.NONE);
            requireValue("10", r);
            require(r.getCoordinatorId() != victim, "job ran on the dead coordinator?");
            waitFor("agreement among survivors", 15_000, () -> agreedCoordinator() > 0);
            waitFor("bootstrap node to drop the dead worker", 15_000, () -> activeCount() == WORKERS - 1);
            say("New coordinator: Worker " + agreedCoordinator() + " (term " + agreedTerm() + ")");
        });

        check("Bootstrap node down: elections and job processing continue", () -> {
            knownWorkers = bootstrapClient.activeWorkers();
            int termBefore = agreedTerm();
            say("Killing the bootstrap node");
            bootstrap.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            for (int i = 0; i < 6; i++) {
                requireValue("76127", client.submit(new PrimeSumJob(1, 1000), ClusterClient.Progress.NONE));
            }
            waitFor("agreement without the bootstrap node", 15_000, () -> agreedCoordinator() > 0);
            require(agreedTerm() > termBefore, "no new term was elected while the bootstrap node was down");
            say("Term " + agreedTerm() + " coordinated by Worker " + agreedCoordinator() + " - elected without the bootstrap node");
        });

        System.out.println();
        System.out.printf("Cluster test: %d passed, %d failed%n", passed, failures.size());
        failures.forEach(f -> System.out.println("  FAILED: " + f));
        return failures.isEmpty() ? 0 : 1;
    }

    private static Map<Integer, Set<Integer>> expectTerms() {
        Map<Integer, Set<Integer>> m = new TreeMap<>();
        m.put(2, new HashSet<>(Arrays.asList(5)));
        m.put(3, new HashSet<>(Arrays.asList(4)));
        return m;
    }

    // ------------------------------------------------------------------ cluster helpers

    private Process spawn(String logName, String... launcherArgs) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("distrilab.Launcher");
        command.addAll(Arrays.asList(launcherArgs));
        File log = logDir.resolve(logName + ".log").toFile();
        Process p = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(log)).start();
        others.add(p);
        return p;
    }

    private void stopAll() {
        for (Process p : others) {
            p.destroyForcibly();
        }
    }

    private int activeCount() {
        try {
            return bootstrapClient.activeWorkers().size();
        } catch (RemoteException e) {
            return -1;
        }
    }

    /** Status of all live workers, asked directly (works even when the bootstrap node is down). */
    private List<WorkerStatus> statuses() {
        List<WorkerStatus> list = new ArrayList<>();
        for (WorkerRef w : knownWorkers) {
            try {
                list.add(w.getStub().getStatus());
            } catch (RemoteException e) {
                // dead worker
            }
        }
        if (knownWorkers.isEmpty() || list.isEmpty()) {
            try {
                return client.networkStatus();
            } catch (RemoteException e) {
                return list;
            }
        }
        return list;
    }

    /** The coordinator every live worker agrees on, or -1. */
    private int agreedCoordinator() {
        List<WorkerStatus> s = statuses();
        Set<String> views = s.stream().map(w -> w.getCoordinatorId() + "@" + w.getTerm()).collect(Collectors.toSet());
        if (s.isEmpty() || views.size() != 1) {
            return -1;
        }
        int id = s.get(0).getCoordinatorId();
        boolean active = s.stream().anyMatch(w -> w.getId() == id && w.isActiveCoordinator());
        return active ? id : -1;
    }

    private int agreedTerm() {
        return agreedCoordinator() > 0 ? statuses().get(0).getTerm() : -1;
    }

    private int jacOf(int workerId) {
        return statuses().stream().filter(w -> w.getId() == workerId).mapToInt(WorkerStatus::getJac).findFirst().orElse(-1);
    }

    private static Job job(JobType type, String input) throws Exception {
        return JobFactory.fromText(type, input);
    }

    // ------------------------------------------------------------------ assertions

    @FunctionalInterface
    private interface Step {
        void run() throws Exception;
    }

    private void check(String name, Step step) {
        System.out.println();
        System.out.println("=== " + name);
        try {
            step.run();
            passed++;
            System.out.println("PASS  " + name);
        } catch (Throwable e) {
            failures.add(name + " -> " + e.getMessage());
            System.out.println("FAIL  " + name + " -> " + e);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void requireValue(String expected, JobResult r) {
        require(new BigDecimal(expected).compareTo(r.getValue()) == 0,
                r.getDescription() + " expected " + expected + " but got " + r.getValueText());
    }

    private static void waitFor(String what, long timeoutMs, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Concurrency.sleep(200);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private static void say(String message) {
        System.out.println(message);
    }

    private static Map<String, String> map(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}

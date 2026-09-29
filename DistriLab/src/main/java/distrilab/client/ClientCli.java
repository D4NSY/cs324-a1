package distrilab.client;

import distrilab.api.DistriLabException;
import distrilab.api.InvalidJobException;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.JobResult;
import distrilab.api.model.WorkerRef;
import distrilab.jobs.CsvLoader;
import distrilab.jobs.Job;
import distrilab.jobs.JobFactory;
import distrilab.jobs.JobType;
import distrilab.util.Config;
import distrilab.util.ConfigException;
import distrilab.util.Log;
import distrilab.util.NamedThreadFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Command-line client - handy for scripted tests, demos over SSH and automation.
 * The GUI ({@link ClientMain}) is the primary client required by the brief.
 */
public final class ClientCli {

    private static final String USAGE = String.join(System.lineSeparator(),
            "Usage: java -jar distrilab.jar cli <command> [--bootstrap=host:port]",
            "  status                         table of all workers, coordinator agreement, overlay connectivity",
            "  coordinator                    show the current coordinator",
            "  submit <TYPE> <args...>        e.g. submit PRIMESUM 1 1000   |   submit MAX 4,8,15,16,23,42",
            "  csv <TYPE> <file.csv>          numbers for MAX / PRIMECOUNT (or start,end for PRIMESUM) from a CSV",
            "  batch <jobs.csv>               one job per line (TYPE,args...), all submitted concurrently",
            "  repeat <N> <TYPE> <args...>    submit N copies of a job concurrently",
            "  elect                          ask a worker to hold an election if no coordinator is active");

    private final Config config;
    private final ClusterClient client;
    private final Object printLock = new Object();

    private ClientCli(Config config) {
        this.config = config;
        this.client = ClusterClient.fromConfig(config);
    }

    public static void main(String[] args) {
        Log.setNode("CLI");
        int exit;
        try {
            Config config = Config.load(args, Config.Role.CLIENT);
            Log.setDebug(config.getBoolean("log.debug"));
            exit = new ClientCli(config).run(config.getPositional());
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            exit = 2;
        }
        System.exit(exit);
    }

    private int run(List<String> args) {
        if (args.isEmpty()) {
            System.out.println(USAGE);
            return 2;
        }
        String command = args.get(0).toLowerCase();
        List<String> rest = args.subList(1, args.size());
        try {
            switch (command) {
                case "status":
                    System.out.println(NetworkReport.format(client.networkStatus()));
                    return 0;
                case "coordinator":
                    CoordinatorInfo info = client.currentCoordinatorInfo();
                    System.out.println(info == null ? "No workers reachable." : "Coordinator: " + info);
                    return 0;
                case "submit":
                    return runJobs(Collections.singletonList(parseJob(rest)));
                case "csv":
                    return runJobs(Collections.singletonList(jobFromCsv(rest)));
                case "batch":
                    requireArgs(rest, 1, "batch <jobs.csv>");
                    return runJobs(CsvLoader.readBatch(Paths.get(rest.get(0))));
                case "repeat":
                    return repeat(rest);
                case "elect":
                    return elect();
                default:
                    System.out.println(USAGE);
                    return 2;
            }
        } catch (InvalidJobException e) {
            System.err.println("Invalid job: " + e.getMessage());
            return 2;
        } catch (RemoteException e) { // (a RemoteException is an IOException, so it is caught first)
            System.err.println("Cannot reach the bootstrap node at " + client.getBootstrapAddress() + ": "
                    + Log.describe(e));
            return 1;
        } catch (IOException e) {
            System.err.println("Could not read file: " + e.getMessage());
            return 1;
        }
    }

    private static Job parseJob(List<String> args) throws InvalidJobException {
        requireArgs(args, 2, "submit <TYPE> <args...>");
        JobType type = JobType.parse(args.get(0));
        return JobFactory.fromText(type, String.join(" ", args.subList(1, args.size())));
    }




    private static Job jobFromCsv(List<String> args) throws InvalidJobException, IOException {
        /*validate argument count and parse target job type*/
        requireArgs(args, 2, "csv <TYPE> <file.csv>");
        JobType type = JobType.parse(args.get(0));
        Path file = Paths.get(args.get(1));

        /*Read dataset and warn user if any non-numeric cells were ignored*/
        CsvLoader.NumberData data = CsvLoader.readNumbers(file);
        if (data.getSkippedCells() > 0) {
            System.out.println("Skipped " + data.getSkippedCells() + " non-numeric cell(s) " + data.getSkippedSamples());
        }

        /*For range-based jobs (e.g., PRIMESUM), restrict inputs to the first two numbers (start, end)*/
        List<String> tokens = data.getTokens();
        if (type.takesRange() && tokens.size() > 2) {
            tokens = tokens.subList(0, 2);
        }

        /* Print summary and instantiate Job via factory */
        System.out.println("Loaded " + tokens.size() + " number(s) from " + file);
        return JobFactory.create(type, tokens);
    }

    private int repeat(List<String> args) throws InvalidJobException {
        requireArgs(args, 3, "repeat <N> <TYPE> <args...>");
        int copies;
        try {
            copies = Integer.parseInt(args.get(0));
        } catch (NumberFormatException e) {
            throw new InvalidJobException("repeat count must be a number, got '" + args.get(0) + "'");
        }
        if (copies < 1 || copies > 1000) {
            throw new InvalidJobException("repeat count must be between 1 and 1000");
        }
        Job job = parseJob(args.subList(1, args.size()));
        return runJobs(Collections.nCopies(copies, job));
    }

    /** Discovery already asks a worker to start an election when no active coordinator is found. */
    private int elect() {
        WorkerRef coordinator = client.discoverCoordinator(ClusterClient.Progress.NONE);
        System.out.println(coordinator != null
                ? "An active coordinator exists (" + coordinator + "); no election needed."
                : "No active coordinator - a worker has been asked to start an election.");
        return 0;
    }

    /** Submits all jobs concurrently (up to client.threads at a time) and prints each result. */
    private int runJobs(List<Job> jobs) {
        int threads = Math.min(jobs.size(), config.getPositiveInt("client.threads"));
        ExecutorService pool = Executors.newFixedThreadPool(threads, new NamedThreadFactory("submit", true));
        List<Future<JobResult>> futures = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            final int number = i + 1;
            final Job job = jobs.get(i);
            futures.add(pool.submit(() -> {
                JobResult result = client.submit(job, msg -> Log.debug("job %d: %s", number, msg));
                synchronized (printLock) {
                    System.out.println("[" + number + "/" + jobs.size() + "] " + result.toReport());
                    System.out.println();
                }
                return result;
            }));
        }
        int failures = 0;
        Map<String, Integer> jobsPerTerm = new TreeMap<>();
        for (int i = 0; i < futures.size(); i++) {
            try {
                JobResult r = futures.get(i).get();
                jobsPerTerm.merge(String.format("term %03d / Worker %d", r.getTerm(), r.getCoordinatorId()), 1,
                        Integer::sum);
            } catch (ExecutionException e) {
                failures++;
                Throwable cause = e.getCause();
                String msg = cause instanceof DistriLabException ? cause.getMessage() : Log.describe(cause);
                synchronized (printLock) {
                    System.err.println("[" + (i + 1) + "/" + jobs.size() + "] FAILED: " + jobs.get(i).describe()
                            + " - " + msg);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures++;
            }
        }
        pool.shutdownNow();
        if (jobs.size() > 1) {
            System.out.println("Summary: " + (jobs.size() - failures) + " succeeded, " + failures + " failed.");
            jobsPerTerm.forEach((term, count) -> System.out.println("  " + term + ": " + count + " job(s)"));
        }
        return failures == 0 ? 0 : 1;
    }

    private static void requireArgs(List<String> args, int min, String usage) throws InvalidJobException {
        if (args.size() < min) {
            throw new InvalidJobException("usage: " + usage);
        }
    }
}

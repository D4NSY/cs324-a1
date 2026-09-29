package distrilab.jobs;

import distrilab.api.InvalidJobException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Reads CSV input for the client.
 * <ul>
 *   <li>{@link #readNumbers} - every numeric cell in the file, in order (headers and other
 *       non-numeric cells are skipped and reported).</li>
 *   <li>{@link #readBatch} - one job per line: {@code TYPE,arg1,arg2,...} e.g.
 *       {@code PRIMESUM,1,1000} or {@code MAX,4,8,15}. Blank lines, lines starting with
 *       '#' and a header line starting with "type" are ignored.</li>
 * </ul>
 */
public final class CsvLoader {

    private CsvLoader() {
    }

    /** Numbers read from a CSV file plus what had to be skipped. */
    public static final class NumberData {
        private final List<String> tokens;
        private final int skippedCells;
        private final List<String> skippedSamples;

        NumberData(List<String> tokens, int skippedCells, List<String> skippedSamples) {
            this.tokens = Collections.unmodifiableList(tokens);
            this.skippedCells = skippedCells;
            this.skippedSamples = Collections.unmodifiableList(skippedSamples);
        }

        public List<String> getTokens() {
            return tokens;
        }

        public int getSkippedCells() {
            return skippedCells;
        }

        public List<String> getSkippedSamples() {
            return skippedSamples;
        }
    }

    public static NumberData readNumbers(Path file) throws IOException {
        List<String> tokens = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int skippedCount = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            for (String cell : splitCells(line)) {
                if (cell.isEmpty()) {
                    continue;
                }
                if (InputParser.isNumber(cell)) {
                    tokens.add(cell);
                } else {
                    skippedCount++;
                    if (skipped.size() < 5) {
                        skipped.add(cell);
                    }
                }
            }
        }
        return new NumberData(tokens, skippedCount, skipped);
    }

    public static List<Job> readBatch(Path file) throws IOException, InvalidJobException {
        List<Job> jobs = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> cells = splitCells(line);
            String first = cells.get(0);
            if (jobs.isEmpty() && (first.equalsIgnoreCase("type") || first.equalsIgnoreCase("job"))) {
                continue; // header row
            }
            try {
                JobType type = JobType.parse(first);
                List<String> args = new ArrayList<>();
                for (String cell : cells.subList(1, cells.size())) {
                    args.addAll(InputParser.tokenize(cell));
                }
                jobs.add(JobFactory.create(type, args));
            } catch (InvalidJobException e) {
                throw new InvalidJobException(file.getFileName() + " line " + (i + 1) + ": " + e.getMessage());
            }
        }
        if (jobs.isEmpty()) {
            throw new InvalidJobException(file.getFileName() + " contains no jobs");
        }
        return jobs;
    }

    private static List<String> splitCells(String line) {
        List<String> cells = new ArrayList<>();
        for (String cell : Arrays.asList(line.split("[,;\\t]"))) {
            cells.add(cell.trim().replace("\"", "").replace("'", ""));
        }
        return cells;
    }
}

package distrilab.client;

import distrilab.api.model.WorkerStatus;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Formats a cluster snapshot as text: a table of workers plus agreement/connectivity checks. */
public final class NetworkReport {

    private NetworkReport() {
    }

    public static String format(List<WorkerStatus> statuses) {
        if (statuses.isEmpty()) {
            return "No active workers.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-7s %-15s %-4s %-12s %-5s %-12s %-6s %-14s %-10s %s%n", "Worker", "Host", "JAC",
                "Role", "Term", "Coordinator", "Jobs", "Neighbours", "Tasks", "Elect(dup)"));
        for (WorkerStatus s : statuses) {
            sb.append(String.format("%-7d %-15s %-4d %-12s %-5d %-12s %-6s %-14s %-10s %d(%d)%n",
                    s.getId(), s.getHost(), s.getJac(), s.role(), s.getTerm(),
                    s.getCoordinatorId() < 0 ? "none" : "Worker " + s.getCoordinatorId(),
                    s.isActiveCoordinator() ? s.getJobsInTerm() + "/" + s.getMaxJobsPerTerm() : "-",
                    s.getNeighbours(), s.getCompletedTasks() + " done",
                    s.getElectionsProcessed(), s.getDuplicatesIgnored()));
        }
        sb.append(agreement(statuses)).append(System.lineSeparator());
        sb.append(connectivity(statuses));
        return sb.toString();
    }

    /** Do all workers agree on one coordinator for the same term? */
    public static String agreement(List<WorkerStatus> statuses) {
        Set<String> views = statuses.stream()
                .map(s -> s.getCoordinatorId() + "@" + s.getTerm())
                .collect(Collectors.toSet());
        if (views.size() == 1) {
            WorkerStatus any = statuses.get(0);
            return any.getCoordinatorId() < 0
                    ? "Agreement: no coordinator yet."
                    : "Agreement: all " + statuses.size() + " workers agree that Worker " + any.getCoordinatorId()
                    + " is the coordinator for term " + any.getTerm() + ".";
        }
        return "Views differ (an election is probably in progress): " + views;
    }

    /** Is the overlay connected (every worker reachable from every other via neighbours)? */
    public static String connectivity(List<WorkerStatus> statuses) {
        Map<Integer, List<Integer>> graph = new HashMap<>();
        statuses.forEach(s -> graph.put(s.getId(), s.getNeighbours()));
        int start = statuses.get(0).getId();
        Set<Integer> seen = new HashSet<>();
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            for (int next : graph.getOrDefault(queue.poll(), java.util.Collections.emptyList())) {
                if (graph.containsKey(next) && seen.add(next)) {
                    queue.add(next);
                }
            }
        }
        long links = statuses.stream().mapToLong(s -> s.getNeighbours().size()).sum() / 2;
        return seen.size() == graph.size()
                ? "Overlay: connected (" + graph.size() + " workers, " + links + " links)."
                : "Overlay: NOT fully connected - reachable from Worker " + start + ": " + seen;
    }

    public static boolean isConnected(List<WorkerStatus> statuses) {
        return !statuses.isEmpty() && connectivity(statuses).startsWith("Overlay: connected");
    }
}

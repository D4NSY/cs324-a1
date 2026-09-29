package distrilab.worker;

import distrilab.api.model.Candidate;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.CoordinatorMessage;
import distrilab.api.model.ElectionMessage;
import distrilab.api.model.ElectionReply;
import distrilab.api.model.WorkerRef;
import distrilab.util.Concurrency;
import distrilab.util.Log;

import java.rmi.RemoteException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The leader-election algorithm: an <b>echo (wave) election over the unstructured
 * overlay</b>, followed by a <b>COORDINATOR flood</b>.
 *
 * <h3>Phase 1 - ELECTION wave (collect)</h3>
 * <ol>
 *   <li>The initiator creates an ELECTION message with a globally unique ID and sends it
 *       to all its neighbours in parallel.</li>
 *   <li>A worker receiving that ID for the <i>first</i> time records it
 *       ({@link DuplicateFilter}), becomes part of the wave, forwards the message to all
 *       neighbours except the sender, and waits for their replies.</li>
 *   <li>A worker receiving an ID it has <i>already</i> processed replies DUPLICATE at once
 *       and does nothing else - its own ballot is already travelling back along the path it
 *       was first reached by.</li>
 *   <li>Each worker replies (the "echo") with the best candidate among itself and
 *       everything its non-duplicate replies reported. Best = lowest JAC, ties broken by
 *       highest ID ({@link Candidate#beats}).</li>
 * </ol>
 * The first-receipt edges form a spanning tree rooted at the initiator, so when the
 * initiator's own call returns it holds the best candidate among <i>all reachable</i>
 * workers - each counted exactly once.
 *
 * <h3>Phase 2 - COORDINATOR flood (announce)</h3>
 * The initiator floods a COORDINATOR message (term number, winner). Each worker processes
 * a given message ID once, adopts the result if the term is newer (or, for the same term,
 * if the winner ranks higher by the same rule) and forwards it to its other neighbours.
 * Because the ranking is a total order that every worker applies identically, concurrent
 * elections for the same term converge on one coordinator.
 */
public final class ElectionManager {

    private final NodeIdentity self;
    private final NeighbourTable neighbours;
    private final CoordinatorRole role;
    private final NeighbourFailureListener failureListener;
    private final ExecutorService messagePool;

    private final DuplicateFilter seenElections;
    private final DuplicateFilter seenAnnouncements;
    private final long electionTimeoutMs;
    private final long hopMarginMs;
    private final long jitterMaxMs;

    private final AtomicBoolean initiating = new AtomicBoolean();
    private final AtomicLong electionSequence = new AtomicLong();
    /** When this worker last processed a new ELECTION message / accepted a COORDINATOR message. */
    private volatile long lastElectionSeen;
    private volatile long lastAnnouncementAccepted;

    /** Guards the check-and-update of {@link #view} together with the role change it implies. */
    private final Object viewLock = new Object();
    private volatile CoordinatorInfo view = CoordinatorInfo.none(0);

    public ElectionManager(NodeIdentity self, NeighbourTable neighbours, CoordinatorRole role,
                           NeighbourFailureListener failureListener, ExecutorService messagePool,
                           long electionTimeoutMs, long hopMarginMs, long jitterMaxMs, long seenTtlMs) {
        this.self = self;
        this.neighbours = neighbours;
        this.role = role;
        this.failureListener = failureListener;
        this.messagePool = messagePool;
        this.electionTimeoutMs = electionTimeoutMs;
        this.hopMarginMs = hopMarginMs;
        this.jitterMaxMs = jitterMaxMs;
        this.seenElections = new DuplicateFilter(seenTtlMs);
        this.seenAnnouncements = new DuplicateFilter(seenTtlMs);
    }

    // ================================================================== initiating

    /**
     * Starts an election in the background unless this worker is already running one.
     *
     * @param jitter wait a random moment first, and skip the election if another worker's
     *               election reaches us in the meantime (avoids a burst of simultaneous
     *               elections when several workers notice a failure at once)
     */
    public void startElection(String reason, boolean jitter) {
        if (!initiating.compareAndSet(false, true)) {
            Log.debug("Election not started (%s): I am already running one", reason);
            return;
        }
        long requestedAt = System.currentTimeMillis();
        messagePool.execute(() -> {
            try {
                if (jitter) {
                    if (!Concurrency.sleepRandom(jitterMaxMs)) {
                        return;
                    }
                    if (lastElectionSeen > requestedAt) {
                        Log.info("Not starting an election (%s): another worker's election is already under way",
                                reason);
                        return;
                    }
                }
                runElection(reason);
            } catch (RuntimeException e) {
                Log.error(e, "Election failed");
            } finally {
                initiating.set(false);
            }
        });
    }

    private void runElection(String reason) {
        String electionId = "E" + self.id() + "-" + electionSequence.incrementAndGet() + "-"
                + Long.toString(System.currentTimeMillis(), 36);
        Log.info(">>> Starting leader election %s (reason: %s)", electionId, reason);
        long startNs = System.nanoTime();

        // The initiator treats itself as the root of the wave.
        ElectionReply result = onElection(ElectionMessage.start(electionId, self.id(), electionTimeoutMs));
        if (result.isDuplicate() || result.getBest() == null) {
            Log.warn("Election %s produced no result", electionId);
            return;
        }
        int newTerm = Math.max(result.getMaxKnownTerm(), view.getTerm()) + 1;
        Candidate winner = result.getBest();
        Log.info("<<< Election %s complete in %d ms: %d reachable worker(s) considered; winner %s for term %d",
                electionId, (System.nanoTime() - startNs) / 1_000_000, result.getParticipants(), winner, newTerm);

        onCoordinator(new CoordinatorMessage("C-" + electionId, electionId, newTerm, winner,
                self.id(), self.id(), result.getParticipants()));
    }

    // ================================================================== ELECTION

    /** Handles an ELECTION message (called via RMI by a neighbour, or locally by the initiator). */
    public ElectionReply onElection(ElectionMessage message) {
        String electionId = message.getElectionId();
        if (!seenElections.firstTime(electionId)) {
            Log.info("ELECTION %s from Worker %d ignored - already processed", electionId, message.getSenderId());
            return ElectionReply.duplicate(self.id());
        }
        lastElectionSeen = System.currentTimeMillis();

        Candidate best = new Candidate(self.id(), role.currentJac(), self.ref());
        int maxTerm = view.getTerm();
        int participants = 1;

        List<WorkerRef> targets = neighbours.snapshotExcluding(message.getSenderId());
        boolean isInitiator = message.getHops() == 0 && message.getSenderId() == self.id();
        Log.info("ELECTION %s %s - my ballot: %s; forwarding to %s", electionId,
                isInitiator ? "initiated by me" : "received from Worker " + message.getSenderId(),
                best, idsOf(targets));

        if (!targets.isEmpty() && message.canForward(hopMarginMs)) {
            ElectionMessage forward = message.forwardedBy(self.id(), hopMarginMs);
            Map<WorkerRef, Future<ElectionReply>> pending = new LinkedHashMap<>();
            for (WorkerRef target : targets) {
                // Parallel forwarding. No lock is held while these remote calls are made.
                pending.put(target, messagePool.submit(() -> target.getStub().onElection(forward)));
            }
            long deadline = System.currentTimeMillis() + forward.getReplyBudgetMs();
            for (Map.Entry<WorkerRef, Future<ElectionReply>> entry : pending.entrySet()) {
                ElectionReply reply = awaitReply(electionId, entry.getKey(), entry.getValue(), deadline);
                if (reply != null && !reply.isDuplicate()) {
                    best = Candidate.best(best, reply.getBest());
                    maxTerm = Math.max(maxTerm, reply.getMaxKnownTerm());
                    participants += reply.getParticipants();
                }
            }
        } else if (!targets.isEmpty()) {
            Log.warn("ELECTION %s: reply budget exhausted after %d hops - not forwarding further",
                    electionId, message.getHops());
        }

        if (!isInitiator) {
            Log.info("ELECTION %s: replying to Worker %d with best %s from %d worker(s) in my branch",
                    electionId, message.getSenderId(), best, participants);
        }
        return ElectionReply.of(self.id(), best, maxTerm, participants);
    }

    private ElectionReply awaitReply(String electionId, WorkerRef from, Future<ElectionReply> future, long deadline) {
        try {
            long wait = Math.max(1, deadline - System.currentTimeMillis());
            return future.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            Log.warn("ELECTION %s: no reply from %s in time - continuing without it", electionId, from);
        } catch (ExecutionException e) {
            Log.warn("ELECTION %s: %s unreachable (%s)", electionId, from, Log.describe(e));
            if (e.getCause() instanceof RemoteException) {
                failureListener.neighbourUnreachable(from);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    // ================================================================== COORDINATOR

    /** Handles a COORDINATOR message (via RMI, or locally from the initiator). */
    public void onCoordinator(CoordinatorMessage message) {
        if (!seenAnnouncements.firstTime(message.getMessageId())) {
            Log.debug("COORDINATOR %s from Worker %d ignored - already processed",
                    message.getMessageId(), message.getSenderId());
            return;
        }
        Candidate winner = message.getWinner();
        boolean accepted;
        synchronized (viewLock) {
            CoordinatorInfo current = view;
            accepted = message.getTerm() > current.getTerm()
                    || (message.getTerm() == current.getTerm()
                        && (!current.hasCoordinator() || winner.beats(current.getCoordinator())));
            if (accepted) {
                view = CoordinatorInfo.of(message.getTerm(), winner);
                // The role change happens under the same lock so that two announcements
                // processed concurrently cannot leave the role and the view disagreeing.
                // (Lock order is always viewLock -> termLock; no remote calls in here.)
                if (winner.getWorkerId() == self.id()) {
                    role.assumeRole(message.getTerm());
                } else {
                    role.relinquishRole("Worker " + winner.getWorkerId() + " was elected for term " + message.getTerm());
                }
            }
        }
        if (!accepted) {
            Log.info("COORDINATOR for term %d (%s) ignored - I already know %s",
                    message.getTerm(), winner, view);
            return;
        }
        Log.info("COORDINATOR: %s is the coordinator for term %d (election %s by Worker %d, %d worker(s) considered)",
                winner, message.getTerm(), message.getElectionId(), message.getInitiatorId(), message.getParticipants());
        lastAnnouncementAccepted = System.currentTimeMillis();

        CoordinatorMessage forward = message.forwardedBy(self.id());
        for (WorkerRef target : neighbours.snapshotExcluding(message.getSenderId())) {
            messagePool.execute(() -> {
                try {
                    target.getStub().onCoordinator(forward);
                } catch (RemoteException e) {
                    Log.warn("Could not forward COORDINATOR to %s (%s)", target, Log.describe(e));
                    failureListener.neighbourUnreachable(target);
                }
            });
        }
    }

    // ================================================================== view helpers

    public CoordinatorInfo currentView() {
        return view;
    }

    /** Adopts the view learned from a neighbour when joining (never makes us coordinator). */
    public void adoptView(CoordinatorInfo learned) {
        if (learned == null || !learned.hasCoordinator()) {
            return;
        }
        synchronized (viewLock) {
            CoordinatorInfo current = view;
            if (learned.getTerm() > current.getTerm() && learned.getCoordinatorId() != self.id()) {
                view = learned;
                Log.info("Current coordinator (learned from my neighbour): %s", learned);
            }
        }
    }

    /** The coordinator stopped answering: forget it (the term number is kept). */
    public void coordinatorFailed(int coordinatorId) {
        synchronized (viewLock) {
            if (view.getCoordinatorId() == coordinatorId) {
                view = CoordinatorInfo.none(view.getTerm());
            }
        }
    }

    /**
     * True while an election is under way as far as this worker can tell: it is initiating
     * one, or it has seen an ELECTION message more recently than the last accepted
     * COORDINATOR message (and not so long ago that the election must have died).
     */
    public boolean electionInProgress() {
        if (initiating.get()) {
            return true;
        }
        long seen = lastElectionSeen;
        return seen > lastAnnouncementAccepted && System.currentTimeMillis() - seen < electionTimeoutMs;
    }

    public void purgeOldMessageIds() {
        seenElections.purgeExpired();
        seenAnnouncements.purgeExpired();
    }

    public long getElectionsProcessed() {
        return seenElections.getProcessedCount();
    }

    public long getDuplicatesIgnored() {
        return seenElections.getDuplicateCount();
    }

    private static List<Integer> idsOf(List<WorkerRef> refs) {
        List<Integer> ids = new java.util.ArrayList<>();
        refs.forEach(r -> ids.add(r.getId()));
        return ids;
    }
}

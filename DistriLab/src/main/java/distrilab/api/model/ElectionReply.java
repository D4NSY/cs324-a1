package distrilab.api.model;

import java.io.Serializable;

/**
 * Reply (the "echo") to an ELECTION message: either DUPLICATE ("I already processed
 * this election - my result travels back along another path") or the best candidate
 * in the replying worker's part of the network, the highest term number seen there
 * and how many workers that part contains.
 */
public final class ElectionReply implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int responderId;
    private final boolean duplicate;
    private final Candidate best;
    private final int maxKnownTerm;
    private final int participants;

    private ElectionReply(int responderId, boolean duplicate, Candidate best, int maxKnownTerm, int participants) {
        this.responderId = responderId;
        this.duplicate = duplicate;
        this.best = best;
        this.maxKnownTerm = maxKnownTerm;
        this.participants = participants;
    }

    public static ElectionReply duplicate(int responderId) {
        return new ElectionReply(responderId, true, null, 0, 0);
    }

    public static ElectionReply of(int responderId, Candidate best, int maxKnownTerm, int participants) {
        return new ElectionReply(responderId, false, best, maxKnownTerm, participants);
    }

    public int getResponderId() {
        return responderId;
    }

    public boolean isDuplicate() {
        return duplicate;
    }

    public Candidate getBest() {
        return best;
    }

    public int getMaxKnownTerm() {
        return maxKnownTerm;
    }

    public int getParticipants() {
        return participants;
    }
}

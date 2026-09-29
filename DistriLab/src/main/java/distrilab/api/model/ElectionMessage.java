package distrilab.api.model;

import java.io.Serializable;

/**
 * ELECTION message. The {@code electionId} is globally unique (initiator ID + local
 * sequence number + timestamp) and is what workers use to recognise duplicates.
 * <p>
 * {@code replyBudgetMs} is how long the sender will wait for this worker's reply; each
 * hop forwards a slightly smaller budget so that a child always answers (or gives up
 * on a silent neighbour) before its parent stops waiting.
 */
public final class ElectionMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String electionId;
    private final int initiatorId;
    private final int senderId;
    private final int hops;
    private final long replyBudgetMs;

    private ElectionMessage(String electionId, int initiatorId, int senderId, int hops, long replyBudgetMs) {
        this.electionId = electionId;
        this.initiatorId = initiatorId;
        this.senderId = senderId;
        this.hops = hops;
        this.replyBudgetMs = replyBudgetMs;
    }

    public static ElectionMessage start(String electionId, int initiatorId, long replyBudgetMs) {
        return new ElectionMessage(electionId, initiatorId, initiatorId, 0, replyBudgetMs);
    }

    /** The copy a worker forwards to its own neighbours. */
    public ElectionMessage forwardedBy(int workerId, long hopMarginMs) {
        return new ElectionMessage(electionId, initiatorId, workerId, hops + 1, replyBudgetMs - hopMarginMs);
    }

    /** False when the remaining budget is too small to wait for another hop. */
    public boolean canForward(long hopMarginMs) {
        return replyBudgetMs - hopMarginMs > hopMarginMs;
    }

    public String getElectionId() {
        return electionId;
    }

    public int getInitiatorId() {
        return initiatorId;
    }

    public int getSenderId() {
        return senderId;
    }

    public int getHops() {
        return hops;
    }

    public long getReplyBudgetMs() {
        return replyBudgetMs;
    }

    @Override
    public String toString() {
        return "ELECTION " + electionId;
    }
}

package distrilab.api.model;

import java.io.Serializable;

/** COORDINATOR message: announces the winner of the election for a term. Immutable. */
public final class CoordinatorMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String messageId;
    private final String electionId;
    private final int term;
    private final Candidate winner;
    private final int initiatorId;
    private final int senderId;
    private final int participants;

    public CoordinatorMessage(String messageId, String electionId, int term, Candidate winner,
                              int initiatorId, int senderId, int participants) {
        if (winner == null) {
            throw new IllegalArgumentException("winner");
        }
        this.messageId = messageId;
        this.electionId = electionId;
        this.term = term;
        this.winner = winner;
        this.initiatorId = initiatorId;
        this.senderId = senderId;
        this.participants = participants;
    }

    public CoordinatorMessage forwardedBy(int workerId) {
        return new CoordinatorMessage(messageId, electionId, term, winner, initiatorId, workerId, participants);
    }

    public String getMessageId() {
        return messageId;
    }

    public String getElectionId() {
        return electionId;
    }

    public int getTerm() {
        return term;
    }

    public Candidate getWinner() {
        return winner;
    }

    public int getInitiatorId() {
        return initiatorId;
    }

    public int getSenderId() {
        return senderId;
    }

    public int getParticipants() {
        return participants;
    }

    @Override
    public String toString() {
        return "COORDINATOR term " + term + ": " + winner;
    }
}

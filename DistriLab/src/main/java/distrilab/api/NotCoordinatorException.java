package distrilab.api;

import distrilab.api.model.CoordinatorInfo;

/**
 * Thrown when a job is sent to a worker that is not (or is no longer) the active
 * coordinator - e.g. its term has just ended. Carries that worker's view of who the
 * coordinator is, so the client can follow the hint instead of rediscovering.
 */
public class NotCoordinatorException extends DistriLabException {
    private static final long serialVersionUID = 1L;

    private final CoordinatorInfo hint;

    public NotCoordinatorException(String message, CoordinatorInfo hint) {
        super(message);
        this.hint = hint;
    }

    /** The rejecting worker's current view of the coordinator; may be empty, never null. */
    public CoordinatorInfo getHint() {
        return hint == null ? CoordinatorInfo.none(0) : hint;
    }
}

package distrilab.worker;

import distrilab.api.model.WorkerRef;

/** Notified when a remote call to a neighbour fails (the overlay then removes and repairs). */
@FunctionalInterface
public interface NeighbourFailureListener {
    void neighbourUnreachable(WorkerRef neighbour);
}

package distrilab.api;

import distrilab.api.model.CoordinatorMessage;
import distrilab.api.model.ElectionMessage;
import distrilab.api.model.ElectionReply;
import distrilab.api.model.TaskResult;
import distrilab.api.model.WorkerRef;
import distrilab.api.model.WorkerStatus;
import distrilab.jobs.Job;

import java.rmi.RemoteException;

/**
 * Worker-to-worker remote interface (extends the client-facing interface because
 * every worker can become the coordinator).
 */
public interface WorkerService extends CoordinatorService {

    int getWorkerId() throws RemoteException;

    /** Liveness check used by heartbeats. */
    boolean ping() throws RemoteException;

    // ---- unstructured overlay -------------------------------------------

    /** Another worker asks to become our neighbour (links are bidirectional). */
    boolean addNeighbour(WorkerRef neighbour) throws RemoteException;

    /** A neighbour is leaving gracefully. */
    void removeNeighbour(int workerId) throws RemoteException;

    // ---- leader election ------------------------------------------------

    /**
     * ELECTION message. The first time a worker sees an election ID it forwards the
     * message to its other neighbours and returns the best candidate found in its part
     * of the network. A repeated ID is answered immediately with a DUPLICATE reply.
     */
    ElectionReply onElection(ElectionMessage message) throws RemoteException;

    /** COORDINATOR message announcing the winner of a term; flooded to all workers. */
    void onCoordinator(CoordinatorMessage message) throws RemoteException;

    // ---- distributed job processing -------------------------------------

    /** Executes one part of a job on this worker's thread pool and returns the partial result. */
    TaskResult executeTask(String taskId, Job task) throws RemoteException, JobExecutionException;

    /** Monitoring snapshot of this worker. */
    WorkerStatus getStatus() throws RemoteException;
}

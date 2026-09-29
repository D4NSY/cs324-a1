package distrilab.api;

import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.JobResult;
import distrilab.jobs.Job;

import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * The client-facing part of every worker. Any worker can tell a client who the
 * coordinator is; only the worker that is currently the coordinator accepts jobs.
 */
public interface CoordinatorService extends Remote {

    /**
     * Submits a job. Blocks until the distributed computation completes.
     *
     * @throws NotCoordinatorException this worker is not the active coordinator (the
     *                                 exception carries a hint about who is)
     * @throws InvalidJobException     the job input is invalid - do not retry
     * @throws JobExecutionException   the job failed while running
     */
    JobResult submitJob(Job job, String clientId)
            throws RemoteException, NotCoordinatorException, InvalidJobException, JobExecutionException;

    /** This worker's current view of the coordinator and term. */
    CoordinatorInfo getCoordinatorInfo() throws RemoteException;

    /** True only while this worker is the coordinator and its term is still open. */
    boolean isActiveCoordinator() throws RemoteException;

    /**
     * Asks this worker to start an election if (and only if) it cannot see an active
     * coordinator. Used by clients that find no coordinator.
     */
    void requestElection(String reason) throws RemoteException;
}

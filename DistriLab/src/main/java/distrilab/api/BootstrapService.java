package distrilab.api;

import distrilab.api.model.RegistrationResult;
import distrilab.api.model.WorkerRef;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.util.List;
import java.util.Set;

/**
 * Remote interface of the Bootstrap Node: the membership directory of the cluster.
 * It keeps the list of currently registered active workers and hands out random
 * contacts. It takes no part in leader elections or job processing.
 */
public interface BootstrapService extends Remote {

    /** Reserves a fresh, never-used worker ID (for workers started without --id). */
    int reserveWorkerId() throws RemoteException;

    /**
     * Registers a worker. The returned result contains one randomly chosen active
     * worker (chosen before the newcomer is added) for the newcomer to link to.
     */
    RegistrationResult registerWorker(WorkerRef worker) throws RemoteException, RegistrationException;

    /**
     * Renews the worker's lease. Returns false if the bootstrap had no record of the
     * worker (e.g. the bootstrap was restarted) - the worker is then re-added.
     */
    boolean renewLease(WorkerRef worker) throws RemoteException;

    /** Graceful departure. */
    void unregisterWorker(int workerId) throws RemoteException;

    /** All workers whose lease is current, sorted by ID. */
    List<WorkerRef> getActiveWorkers() throws RemoteException;

    /** A random active worker whose ID is not in {@code excludeIds}, or null if none. */
    WorkerRef getRandomWorker(Set<Integer> excludeIds) throws RemoteException;

    /** A worker could not reach {@code workerId}; the bootstrap verifies and removes it. */
    void reportSuspectedFailure(int workerId) throws RemoteException;
}

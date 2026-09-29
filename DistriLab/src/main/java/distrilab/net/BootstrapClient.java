package distrilab.net;

import distrilab.api.BootstrapService;
import distrilab.api.RegistrationException;
import distrilab.api.model.RegistrationResult;
import distrilab.api.model.WorkerRef;
import distrilab.util.Config;

import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client-side facade for the bootstrap node, used by workers and clients. It looks the
 * service up in the bootstrap's RMI registry, caches the stub, and on a failed call looks
 * it up once more and retries - so a restarted bootstrap node is picked up transparently.
 */
public final class BootstrapClient {

    private final String host;
    private final int port;
    private final String serviceName;
    private volatile BootstrapService cached;

    public BootstrapClient(String host, int port, String serviceName) {
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
    }

    public static BootstrapClient fromConfig(Config config) {
        return new BootstrapClient(config.get("bootstrap.host"), config.getPositiveInt("bootstrap.port"),
                config.get("bootstrap.serviceName"));
    }

    public String getAddress() {
        return host + ":" + port;
    }

    @FunctionalInterface
    private interface Call<T, E extends Exception> {
        T apply(BootstrapService service) throws RemoteException, E;
    }

    private <T, E extends Exception> T call(Call<T, E> call) throws RemoteException, E {
        try {
            return call.apply(service());
        } catch (RemoteException first) {
            cached = null; // stale stub (bootstrap restarted?) - look it up again once
            return call.apply(service());
        }
    }

    private BootstrapService service() throws RemoteException {
        BootstrapService service = cached;
        if (service == null) {
            try {
                Registry registry = LocateRegistry.getRegistry(host, port);
                service = (BootstrapService) registry.lookup(serviceName);
            } catch (NotBoundException e) {
                throw new RemoteException("No service named '" + serviceName + "' in the registry at " + getAddress());
            }
            cached = service;
        }
        return service;
    }

    public int reserveWorkerId() throws RemoteException {
        return call(BootstrapService::reserveWorkerId);
    }

    public RegistrationResult register(WorkerRef self) throws RemoteException, RegistrationException {
        return this.<RegistrationResult, RegistrationException>call(s -> s.registerWorker(self));
    }

    public boolean renewLease(WorkerRef self) throws RemoteException {
        return call(s -> s.renewLease(self));
    }

    public void unregister(int workerId) throws RemoteException {
        call(s -> {
            s.unregisterWorker(workerId);
            return null;
        });
    }

    public List<WorkerRef> activeWorkers() throws RemoteException {
        return call(BootstrapService::getActiveWorkers);
    }

    public WorkerRef randomWorker(Set<Integer> excludeIds) throws RemoteException {
        // Always send a plain HashSet copy: some Set views would serialise their whole backing map.
        Set<Integer> exclude = excludeIds == null ? new HashSet<>() : new HashSet<>(excludeIds);
        return call(s -> s.getRandomWorker(exclude));
    }

    public void reportSuspectedFailure(int workerId) throws RemoteException {
        call(s -> {
            s.reportSuspectedFailure(workerId);
            return null;
        });
    }
}

package distrilab.net;

import distrilab.util.Config;

import java.rmi.NoSuchObjectException;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;

/** Java RMI set-up shared by all process types. */
public final class RmiSupport {

    private RmiSupport() {
    }

    /**
     * Must run before any RMI activity. Sets the address embedded in exported stubs and
     * the client-side response/handshake timeouts.
     */
    public static void configure(Config config) {
        String host = config.get("rmi.hostname");
        if (!host.isEmpty()) {
            System.setProperty("java.rmi.server.hostname", host);
        }
        System.setProperty("sun.rmi.transport.tcp.responseTimeout", config.get("rmi.responseTimeoutMs"));
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout", config.get("rmi.connectTimeoutMs"));
    }

    /** Exports a remote object on the given port (0 = any) with a connect-timeout socket factory. */
    @SuppressWarnings("unchecked")
    public static <T extends Remote> T export(T object, int port, Config config) throws RemoteException {
        TimeoutClientSocketFactory csf = new TimeoutClientSocketFactory(config.getPositiveInt("rmi.connectTimeoutMs"));
        return (T) UnicastRemoteObject.exportObject(object, port, csf, null);
    }

    public static void unexportQuietly(Remote object) {
        try {
            UnicastRemoteObject.unexportObject(object, true);
        } catch (NoSuchObjectException ignored) {
            // already unexported
        }
    }
}

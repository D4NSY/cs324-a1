package distrilab.net;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.rmi.server.RMIClientSocketFactory;

/**
 * Client socket factory with a connect timeout. It travels inside every stub we export,
 * so a call to a crashed or unreachable machine fails within a few seconds instead of
 * waiting for the operating system's (much longer) TCP timeout. Fast failure detection
 * is what lets heartbeats and elections react quickly.
 */
public final class TimeoutClientSocketFactory implements RMIClientSocketFactory, Serializable {
    private static final long serialVersionUID = 1L;

    private final int connectTimeoutMs;

    public TimeoutClientSocketFactory(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket.setTcpNoDelay(true);
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    // RMI reuses connections per (host, port, factory), so equality matters.
    @Override
    public boolean equals(Object o) {
        return o instanceof TimeoutClientSocketFactory
                && ((TimeoutClientSocketFactory) o).connectTimeoutMs == connectTimeoutMs;
    }

    @Override
    public int hashCode() {
        return Integer.hashCode(connectTimeoutMs);
    }
}

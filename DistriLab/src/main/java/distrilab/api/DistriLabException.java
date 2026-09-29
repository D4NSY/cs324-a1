package distrilab.api;

/**
 * Base class of all checked, application-level exceptions that can cross an RMI
 * boundary. (Transport failures are reported separately as RemoteException.)
 */
public class DistriLabException extends Exception {
    private static final long serialVersionUID = 1L;

    public DistriLabException(String message) {
        super(message);
    }

    public DistriLabException(String message, Throwable cause) {
        super(message, cause);
    }
}

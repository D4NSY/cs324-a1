package distrilab.api;

/** The bootstrap node refused to register a worker (for example, a duplicate ID). */
public class RegistrationException extends DistriLabException {
    private static final long serialVersionUID = 1L;

    public RegistrationException(String message) {
        super(message);
    }
}

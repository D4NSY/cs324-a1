package distrilab.api;

/** The job's input is malformed or outside the configured limits. Never retried. */
public class InvalidJobException extends DistriLabException {
    private static final long serialVersionUID = 1L;

    public InvalidJobException(String message) {
        super(message);
    }
}

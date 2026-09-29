package distrilab.api;

/** A job or sub-task was accepted but its computation failed. */
public class JobExecutionException extends DistriLabException {
    private static final long serialVersionUID = 1L;

    public JobExecutionException(String message) {
        super(message);
    }
}

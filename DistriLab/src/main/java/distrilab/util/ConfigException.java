package distrilab.util;

/**
 * Thrown when configuration is missing, malformed or contains an unknown key.
 * Unchecked because a bad configuration is a start-up error the launcher reports
 * and exits on; there is nothing sensible for intermediate code to recover.
 */
public class ConfigException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}

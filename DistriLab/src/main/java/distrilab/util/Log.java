package distrilab.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/**
 * Minimal thread-safe console logger. Every line shows the time, the node name and the
 * thread name - the thread name makes the concurrency in the system visible in the logs.
 */
public final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static volatile String node = "main";
    private static volatile boolean debugEnabled;
    private static volatile Consumer<String> mirror;

    private Log() {
    }

    public static void setNode(String name) {
        node = name;
    }

    public static void setDebug(boolean enabled) {
        debugEnabled = enabled;
    }

    public static boolean isDebug() {
        return debugEnabled;
    }

    /** Optionally copies every log line somewhere else (the client GUI uses this). */
    public static void setMirror(Consumer<String> consumer) {
        mirror = consumer;
    }

    public static void info(String format, Object... args) {
        emit("INFO", format, args, null);
    }

    public static void warn(String format, Object... args) {
        emit("WARN", format, args, null);
    }

    public static void error(String format, Object... args) {
        emit("ERROR", format, args, null);
    }

    public static void error(Throwable t, String format, Object... args) {
        emit("ERROR", format, args, t);
    }

    public static void debug(String format, Object... args) {
        if (debugEnabled) {
            emit("DEBUG", format, args, null);
        }
    }

    /** The most useful single-line description of an exception (skips RMI wrapper noise). */
    public static String describe(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        return root.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }

    private static void emit(String level, String format, Object[] args, Throwable t) {
        String message = (args == null || args.length == 0) ? format : String.format(format, args);
        String line = String.format("%s %-5s [%s] (%s) %s",
                LocalTime.now().format(TIME), level, node, Thread.currentThread().getName(), message);
        synchronized (LOCK) {
            System.out.println(line);
            if (t != null) {
                if (debugEnabled) {
                    StringWriter sw = new StringWriter();
                    t.printStackTrace(new PrintWriter(sw));
                    System.out.print(sw);
                } else {
                    System.out.println("        caused by " + describe(t));
                }
            }
        }
        Consumer<String> m = mirror;
        if (m != null) {
            m.accept(line);
        }
    }
}

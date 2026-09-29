package distrilab.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Layered, immutable configuration.
 * <ol>
 *   <li>Built-in defaults: {@code distrilab-defaults.properties} inside the jar.</li>
 *   <li>Deployment file: {@code config/distrilab.properties} (or {@code --config=path}).</li>
 *   <li>Command-line overrides: {@code --key=value}, plus a few friendly aliases.</li>
 * </ol>
 * Nothing in the code base hard-codes ports, host names, thread counts, timeouts or
 * the term length - it all comes from here.
 */
public final class Config {

    public static final String DEFAULTS_RESOURCE = "/distrilab-defaults.properties";
    public static final String DEFAULT_FILE = "config/distrilab.properties";

    /** The role of the process, used to resolve role-specific aliases such as --port. */
    public enum Role { BOOTSTRAP, WORKER, CLIENT, TOOL }

    private final Properties properties;
    private final List<String> positional;
    private final String source;

    private Config(Properties properties, List<String> positional, String source) {
        this.properties = properties;
        this.positional = Collections.unmodifiableList(positional);
        this.source = source;
    }

    /** Builds a configuration from the built-in defaults, the optional file and the arguments. */
    public static Config load(String[] args, Role role) {
        Properties props = loadDefaults();

        Map<String, String> overrides = new LinkedHashMap<>();
        List<String> positional = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--") && arg.length() > 2) {
                String body = arg.substring(2);
                int eq = body.indexOf('=');
                String key = eq < 0 ? body : body.substring(0, eq);
                String value = eq < 0 ? "true" : body.substring(eq + 1);
                overrides.put(key.trim(), value.trim());
            } else {
                positional.add(arg);
            }
        }

        String source = "built-in defaults";
        String explicitFile = overrides.remove("config");
        Path file = Paths.get(explicitFile != null ? explicitFile : DEFAULT_FILE);
        if (Files.isRegularFile(file)) {
            mergeFile(props, file);
            source = file.toAbsolutePath().normalize().toString();
        } else if (explicitFile != null) {
            throw new ConfigException("Configuration file not found: " + file.toAbsolutePath());
        }

        for (Map.Entry<String, String> e : overrides.entrySet()) {
            applyOverride(props, e.getKey(), e.getValue(), role);
        }
        return new Config(props, positional, source);
    }

    /** Configuration with defaults only - used by tests and tools. */
    public static Config defaults() {
        return new Config(loadDefaults(), new ArrayList<>(), "built-in defaults");
    }

    /** Returns a copy of this configuration with some keys replaced (used by tests). */
    public Config with(Map<String, String> changes) {
        Properties copy = new Properties();
        copy.putAll(properties);
        for (Map.Entry<String, String> e : changes.entrySet()) {
            requireKnown(copy, e.getKey());
            copy.setProperty(e.getKey(), e.getValue());
        }
        return new Config(copy, new ArrayList<>(positional), source);
    }

    private static Properties loadDefaults() {
        Properties props = new Properties();
        try (InputStream in = Config.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
            if (in == null) {
                throw new ConfigException("Built-in defaults " + DEFAULTS_RESOURCE
                        + " are missing from the classpath - rebuild the jar with scripts/build");
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                props.load(reader);
            }
        } catch (IOException e) {
            throw new ConfigException("Could not read built-in defaults: " + e.getMessage(), e);
        }
        return props;
    }

    private static void mergeFile(Properties props, Path file) {
        Properties fromFile = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            fromFile.load(reader);
        } catch (IOException e) {
            throw new ConfigException("Could not read " + file + ": " + e.getMessage(), e);
        }
        for (String key : fromFile.stringPropertyNames()) {
            requireKnown(props, key);
            props.setProperty(key, fromFile.getProperty(key).trim());
        }
    }

    private static void applyOverride(Properties props, String key, String value, Role role) {
        switch (key) {
            case "id":
                props.setProperty("worker.id", value);
                break;
            case "host":
                props.setProperty("rmi.hostname", value);
                break;
            case "port":
                props.setProperty(role == Role.BOOTSTRAP ? "bootstrap.port" : "worker.port", value);
                break;
            case "threads":
                props.setProperty("worker.threads", value);
                break;
            case "debug":
                props.setProperty("log.debug", value);
                break;
            case "bootstrap": {
                int colon = value.lastIndexOf(':');
                if (colon <= 0 || colon == value.length() - 1) {
                    throw new ConfigException("--bootstrap must look like host:port, e.g. 192.168.1.20:1099");
                }
                props.setProperty("bootstrap.host", value.substring(0, colon));
                props.setProperty("bootstrap.port", value.substring(colon + 1));
                break;
            }
            default:
                requireKnown(props, key);
                props.setProperty(key, value);
        }
    }

    private static void requireKnown(Properties props, String key) {
        if (!props.containsKey(key)) {
            throw new ConfigException("Unknown option '" + key + "'. Valid keys are listed in "
                    + DEFAULT_FILE + " and src/main/resources" + DEFAULTS_RESOURCE);
        }
    }

    // ------------------------------------------------------------------ getters

    public String get(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new ConfigException("Missing configuration key '" + key + "'");
        }
        return value.trim();
    }

    public int getInt(String key) {
        String value = get(key);
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("Configuration key '" + key + "' must be a whole number but was '" + value + "'");
        }
    }

    public long getLong(String key) {
        String value = get(key);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new ConfigException("Configuration key '" + key + "' must be a whole number but was '" + value + "'");
        }
    }

    public int getPositiveInt(String key) {
        int value = getInt(key);
        if (value <= 0) {
            throw new ConfigException("Configuration key '" + key + "' must be greater than zero but was " + value);
        }
        return value;
    }

    public boolean getBoolean(String key) {
        String value = get(key).toLowerCase();
        if (value.equals("true") || value.equals("yes") || value.equals("1")) {
            return true;
        }
        if (value.equals("false") || value.equals("no") || value.equals("0")) {
            return false;
        }
        throw new ConfigException("Configuration key '" + key + "' must be true or false but was '" + value + "'");
    }

    /** Arguments that were not --options, e.g. the job given to the command-line client. */
    public List<String> getPositional() {
        return positional;
    }

    /** Where the deployment settings came from (for start-up logging). */
    public String getSource() {
        return source;
    }
}

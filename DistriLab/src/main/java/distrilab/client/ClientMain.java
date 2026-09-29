package distrilab.client;

import distrilab.client.gui.ClientWindow;
import distrilab.util.Config;
import distrilab.util.ConfigException;
import distrilab.util.Log;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;

/** Entry point of the client GUI (one client = one JVM process; run as many as you like). */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) {
        Log.setNode("Client");
        Config config;
        try {
            config = Config.load(args, Config.Role.CLIENT);
        } catch (ConfigException e) {
            System.err.println("Configuration error: " + e.getMessage());
            System.exit(2);
            return;
        }
        Log.setDebug(config.getBoolean("log.debug"));
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No graphical display is available. Use the command-line client instead:");
            System.err.println("  java -jar distrilab.jar cli");
            System.exit(2);
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception e) {
                Log.debug("Using the default look and feel (%s)", e.getMessage());
            }
            new ClientWindow(config).setVisible(true);
        });
    }
}

package distrilab;

import distrilab.bootstrap.BootstrapNode;
import distrilab.client.ClientCli;
import distrilab.client.ClientMain;
import distrilab.selftest.ClusterTest;
import distrilab.selftest.UnitTests;
import distrilab.worker.WorkerNode;

import java.util.Arrays;

/**
 * Single entry point for distrilab.jar:
 * <pre>
 *   java -jar distrilab.jar bootstrap            start the bootstrap node
 *   java -jar distrilab.jar worker --id=3        start a worker
 *   java -jar distrilab.jar client               start the Swing client GUI
 *   java -jar distrilab.jar cli submit MAX 4,8   command-line client
 *   java -jar distrilab.jar test                 unit tests
 *   java -jar distrilab.jar cluster-test         end-to-end test on a local cluster
 * </pre>
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        switch (args[0].toLowerCase()) {
            case "bootstrap":
                BootstrapNode.main(rest);
                break;
            case "worker":
                WorkerNode.main(rest);
                break;
            case "client":
            case "gui":
                ClientMain.main(rest);
                break;
            case "cli":
                ClientCli.main(rest);
                break;
            case "test":
                UnitTests.main(rest);
                break;
            case "cluster-test":
                ClusterTest.main(rest);
                break;
            default:
                usage();
                System.exit(2);
        }
    }

    private static void usage() {
        System.out.println(String.join(System.lineSeparator(),
                "DistriLab - distributed computing cluster (Java RMI)",
                "Usage: java -jar distrilab.jar <command> [--key=value ...]",
                "  bootstrap                 start the bootstrap node",
                "  worker [--id=N]           start a worker (ID assigned by the bootstrap node if omitted)",
                "  client                    start the client GUI",
                "  cli <command>             command-line client (run 'cli' alone for its commands)",
                "  test                      run the unit tests",
                "  cluster-test              start a local cluster and run end-to-end tests",
                "Common options:",
                "  --bootstrap=host:port     where the bootstrap node is (default 127.0.0.1:1099)",
                "  --host=IP                 this machine's address as seen by the others (multi-machine runs)",
                "  --port=N                  RMI port (bootstrap: registry port; worker: export port)",
                "  --config=path             alternative configuration file",
                "  any key from config/distrilab.properties, e.g. --worker.threads=8"));
    }
}

package distrilab.client.gui;

import distrilab.api.model.WorkerStatus;
import distrilab.client.ClusterClient;
import distrilab.client.NetworkReport;
import distrilab.util.Log;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.rmi.RemoteException;
import java.util.List;

/** Shows every worker's status: JAC, role, term, neighbours, task counters. */
final class NetworkDialog extends JDialog {
    private static final long serialVersionUID = 1L;

    private static final String[] COLUMNS = {"Worker", "Host", "JAC", "Role", "Term", "Coordinator",
        "Jobs/term", "Neighbours", "Tasks done", "Peak tasks", "Elections (dup.)"};
    private static final int[] WIDTHS = {55, 95, 40, 110, 45, 85, 70, 110, 75, 75, 100};

    private final transient ClusterClient client;
    private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
        private static final long serialVersionUID = 1L;

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    };
    private final JLabel agreement = new JLabel(" ");
    private final JLabel connectivity = new JLabel(" ");

    NetworkDialog(JFrame owner, ClusterClient client) {
        super(owner, "Network status", false);
        this.client = client;
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        for (int i = 0; i < WIDTHS.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(WIDTHS[i]);
        }

        JPanel south = new JPanel(new BorderLayout());
        JPanel labels = new JPanel(new BorderLayout());
        labels.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        labels.add(agreement, BorderLayout.NORTH);
        labels.add(connectivity, BorderLayout.SOUTH);
        south.add(labels, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refresh());
        JButton close = new JButton("Close");
        close.addActionListener(e -> dispose());
        buttons.add(refresh);
        buttons.add(close);
        south.add(buttons, BorderLayout.EAST);

        getContentPane().add(new JScrollPane(table), BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        setSize(1050, 320);
        setLocationRelativeTo(owner);
        refresh();
    }

    private void refresh() {
        agreement.setText("Loading...");
        Thread loader = new Thread(() -> {
            try {
                List<WorkerStatus> statuses = client.networkStatus();
                SwingUtilities.invokeLater(() -> show(statuses));
            } catch (RemoteException e) {
                SwingUtilities.invokeLater(() -> agreement.setText("Cannot reach the bootstrap node: " + Log.describe(e)));
            }
        }, "network-status");
        loader.setDaemon(true);
        loader.start();
    }

    private void show(List<WorkerStatus> statuses) {
        model.setRowCount(0);
        for (WorkerStatus s : statuses) {
            model.addRow(new Object[]{s.getId(), s.getHost(), s.getJac(), s.role(), s.getTerm(),
                s.getCoordinatorId() < 0 ? "none" : "Worker " + s.getCoordinatorId(),
                s.isActiveCoordinator() ? s.getJobsInTerm() + " / " + s.getMaxJobsPerTerm() : "-",
                s.getNeighbours().toString(), s.getCompletedTasks(), s.getPeakConcurrentTasks(),
                s.getElectionsProcessed() + " (" + s.getDuplicatesIgnored() + ")"});
        }
        if (statuses.isEmpty()) {
            agreement.setText("No active workers.");
            connectivity.setText(" ");
        } else {
            agreement.setText(NetworkReport.agreement(statuses));
            connectivity.setText(NetworkReport.connectivity(statuses));
        }
    }
}

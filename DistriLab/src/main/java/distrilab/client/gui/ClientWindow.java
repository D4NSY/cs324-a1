package distrilab.client.gui;

import distrilab.api.DistriLabException;
import distrilab.api.InvalidJobException;
import distrilab.api.model.CoordinatorInfo;
import distrilab.api.model.JobResult;
import distrilab.client.ClusterClient;
import distrilab.jobs.CsvLoader;
import distrilab.jobs.InputParser;
import distrilab.jobs.Job;
import distrilab.jobs.JobFactory;
import distrilab.jobs.JobType;
import distrilab.net.BootstrapClient;
import distrilab.util.Config;
import distrilab.util.Log;
import distrilab.util.NamedThreadFactory;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main window of the client GUI.
 * <p>
 * Threading rules (Swing is single-threaded):
 * <ul>
 *   <li>All components and the table model are touched only on the event dispatch thread
 *       (EDT).</li>
 *   <li>Anything slow - reading CSV files, parsing large inputs, talking to the cluster - runs
 *       on background threads ({@code submitPool}, {@code ioPool}) and hands its result back
 *       with {@link SwingUtilities#invokeLater}.</li>
 * </ul>
 * Each submitted job gets its own pool thread, so many jobs run at once and the window stays
 * responsive.
 */
public final class ClientWindow extends JFrame {
    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String CARD_RANGE = "range";
    private static final String CARD_LIST = "list";

    private final Config config;
    private final int textAreaLimit;
    private final long randomMax;
    private final ExecutorService submitPool;
    private final ExecutorService ioPool = Executors.newSingleThreadExecutor(new NamedThreadFactory("client-io", true));
    private final ExecutorService statusPool = Executors.newSingleThreadExecutor(new NamedThreadFactory("client-status", true));
    private final AtomicBoolean statusRefreshRunning = new AtomicBoolean();
    private volatile ClusterClient client;

    // ---- connection bar
    private final JTextField hostField = new JTextField(12);
    private final JTextField portField = new JTextField(5);
    private final JLabel coordinatorLabel = new JLabel("Coordinator: -");

    // ---- job form
    private final JComboBox<JobType> typeBox = new JComboBox<>(JobType.values());
    private final JLabel typeDescription = new JLabel();
    private final CardLayout inputCards = new CardLayout();
    private final JPanel inputPanel = new JPanel(inputCards);
    private final JTextField startField = new JTextField("1", 10);
    private final JTextField endField = new JTextField("1000", 10);
    private final JTextArea numbersArea = new JTextArea(10, 26);
    private final JLabel sourceLabel = new JLabel("Manual entry");
    private final JSpinner copiesSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 50, 1));
    private final JButton submitButton = new JButton("Submit job");
    /** Numbers loaded from a file / generated, when too many to show in the text area (EDT only). */
    private List<String> loadedTokens;

    // ---- results
    private final JobTableModel tableModel = new JobTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea detailsArea = new JTextArea();
    private final JTextArea logArea = new JTextArea();
    private final JLabel summaryLabel = new JLabel(" ");

    public ClientWindow(Config config) {
        super("DistriLab Client");
        this.config = config;
        this.textAreaLimit = config.getPositiveInt("client.textAreaLimit");
        this.randomMax = config.getLong("client.randomMax");
        this.submitPool = Executors.newFixedThreadPool(config.getPositiveInt("client.threads"),
                new NamedThreadFactory("submit", true));
        this.client = ClusterClient.fromConfig(config);
        setTitle("DistriLab Client - " + client.getClientId());

        hostField.setText(config.get("bootstrap.host"));
        portField.setText(config.get("bootstrap.port"));

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildJobForm(), buildResults());
        main.setResizeWeight(0.0);
        main.setDividerLocation(400);

        getContentPane().setLayout(new BorderLayout(6, 6));
        getContentPane().add(buildConnectionBar(), BorderLayout.NORTH);
        getContentPane().add(main, BorderLayout.CENTER);
        getContentPane().add(wrap(summaryLabel), BorderLayout.SOUTH);

        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                submitPool.shutdownNow();
                ioPool.shutdownNow();
                statusPool.shutdownNow();
                System.exit(0);
            }
        });
        setMinimumSize(new Dimension(1000, 620));
        setSize(1200, 720);
        setLocationByPlatform(true);

        Log.setMirror(line -> SwingUtilities.invokeLater(() -> appendLog(line)));
        onTypeChanged();
        log("Client " + client.getClientId() + " started; bootstrap node " + client.getBootstrapAddress());

        Timer timer = new Timer(config.getPositiveInt("client.statusRefreshMs"), e -> refreshCoordinator());
        timer.setInitialDelay(300);
        timer.start();
    }

    // ================================================================== layout

    private JComponent buildConnectionBar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.add(new JLabel("Bootstrap node:"));
        bar.add(hostField);
        bar.add(new JLabel(":"));
        bar.add(portField);
        JButton connect = new JButton("Connect");
        connect.addActionListener(e -> reconnect());
        bar.add(connect);
        bar.add(Box.createHorizontalStrut(20));
        coordinatorLabel.setFont(coordinatorLabel.getFont().deriveFont(Font.BOLD));
        bar.add(coordinatorLabel);
        bar.add(Box.createHorizontalStrut(20));
        JButton network = new JButton("Network status...");
        network.addActionListener(e -> new NetworkDialog(this, client).setVisible(true));
        bar.add(network);
        return bar;
    }

    private JComponent buildJobForm() {
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("New job"), BorderFactory.createEmptyBorder(4, 6, 6, 6)));
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 2, 3, 2);

        typeBox.setRenderer(new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                JobType type = (JobType) value;
                return super.getListCellRendererComponent(list, type == null ? "" : type.getSignature(),
                        index, isSelected, cellHasFocus);
            }
        });
        typeBox.addActionListener(e -> onTypeChanged());
        form.add(labelled("Job type", typeBox), c);
        c.gridy++;
        typeDescription.setFont(typeDescription.getFont().deriveFont(Font.ITALIC));
        form.add(typeDescription, c);

        JPanel range = new JPanel(new GridBagLayout());
        GridBagConstraints rc = new GridBagConstraints();
        rc.insets = new Insets(2, 2, 2, 6);
        rc.anchor = GridBagConstraints.WEST;
        range.add(new JLabel("Start"), rc);
        range.add(startField, rc);
        rc.gridy = 1;
        range.add(new JLabel("End"), rc);
        range.add(endField, rc);
        JPanel rangeWrapper = new JPanel(new BorderLayout());
        rangeWrapper.add(range, BorderLayout.NORTH);

        numbersArea.setLineWrap(true);
        numbersArea.setWrapStyleWord(true);
        numbersArea.setText("12, 7, 19, 4, 23, 16, 29, 8, 31, 2");
        JPanel list = new JPanel(new BorderLayout(0, 4));
        list.add(new JLabel("Numbers (separated by commas, spaces or new lines):"), BorderLayout.NORTH);
        list.add(new JScrollPane(numbersArea), BorderLayout.CENTER);

        inputPanel.add(rangeWrapper, CARD_RANGE);
        inputPanel.add(list, CARD_LIST);
        c.gridy++;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        form.add(inputPanel, c);
        c.weighty = 0;
        c.fill = GridBagConstraints.HORIZONTAL;

        c.gridy++;
        sourceLabel.setForeground(new java.awt.Color(0x55, 0x55, 0x55));
        form.add(sourceLabel, c);

        JPanel dataButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton loadCsv = new JButton("Load CSV...");
        loadCsv.setToolTipText("Load numbers (or start,end for PRIMESUM) from a CSV file");
        loadCsv.addActionListener(e -> loadCsv());
        JButton random = new JButton("Random...");
        random.setToolTipText("Generate a list of random numbers");
        random.addActionListener(e -> generateRandom());
        JButton clear = new JButton("Clear");
        clear.addActionListener(e -> clearInput());
        dataButtons.add(loadCsv);
        dataButtons.add(random);
        dataButtons.add(clear);
        c.gridy++;
        form.add(dataButtons, c);

        c.gridy++;
        form.add(labelled("Copies to submit at once", copiesSpinner), c);

        submitButton.setFont(submitButton.getFont().deriveFont(Font.BOLD, 14f));
        submitButton.addActionListener(e -> submitFromForm());
        c.gridy++;
        c.insets = new Insets(10, 2, 4, 2);
        form.add(submitButton, c);

        JButton batch = new JButton("Submit batch CSV...");
        batch.setToolTipText("CSV with one job per line, e.g. PRIMESUM,1,1000 or MAX,4,8,15 - all run concurrently");
        batch.addActionListener(e -> submitBatch());
        c.gridy++;
        c.insets = new Insets(4, 2, 3, 2);
        form.add(batch, c);
        return form;
    }

    private JComponent buildResults() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        int[] widths = {36, 220, 170, 150, 200, 75, 65};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showDetails();
            }
        });
        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createTitledBorder("Jobs (select a row for details)"));

        detailsArea.setEditable(false);
        detailsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Details", new JScrollPane(detailsArea));
        tabs.addTab("Log", new JScrollPane(logArea));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, tabs);
        split.setResizeWeight(0.55);
        split.setDividerLocation(360);
        return split;
    }

    private static JComponent labelled(String label, JComponent field) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(field, BorderLayout.CENTER);
        return p;
    }

    private static JComponent wrap(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createEmptyBorder(2, 8, 4, 8));
        p.add(c);
        return p;
    }

    // ================================================================== input handling

    private JobType selectedType() {
        return (JobType) typeBox.getSelectedItem();
    }

    private void onTypeChanged() {
        JobType type = selectedType();
        typeDescription.setText(type.getDescription());
        inputCards.show(inputPanel, type.takesRange() ? CARD_RANGE : CARD_LIST);
    }

    private void clearInput() {
        loadedTokens = null;
        numbersArea.setEditable(true);
        numbersArea.setText("");
        sourceLabel.setText("Manual entry");
    }

    /** Shows numbers in the text area, or keeps them aside if there are too many to display. */
    private void useTokens(List<String> tokens, String origin) {
        if (tokens.size() <= textAreaLimit) {
            loadedTokens = null;
            numbersArea.setEditable(true);
            numbersArea.setText(String.join(", ", tokens));
        } else {
            loadedTokens = tokens;
            numbersArea.setEditable(false);
            numbersArea.setText(String.format("%,d numbers loaded from %s.%n%nFirst values: %s ...%n%n"
                            + "(Too many to display. Press Clear to type numbers by hand.)",
                    tokens.size(), origin, String.join(", ", tokens.subList(0, 20))));
        }
        sourceLabel.setText(String.format("%,d numbers from %s", tokens.size(), origin));
    }

    private void loadCsv() {
        JFileChooser chooser = new JFileChooser(defaultDirectory());
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        JobType type = selectedType();
        sourceLabel.setText("Reading " + file.getFileName() + "...");
        ioPool.execute(() -> {
            try {
                CsvLoader.NumberData data = CsvLoader.readNumbers(file);
                SwingUtilities.invokeLater(() -> applyCsv(type, file, data));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    sourceLabel.setText("Could not read " + file.getFileName());
                    error("Could not read " + file + ":\n" + e.getMessage());
                });
            }
        });
    }

    private void applyCsv(JobType type, Path file, CsvLoader.NumberData data) {
        List<String> tokens = data.getTokens();
        if (tokens.isEmpty()) {
            error(file.getFileName() + " contains no numbers.");
            sourceLabel.setText("Manual entry");
            return;
        }
        if (type.takesRange()) {
            if (tokens.size() < 2) {
                error("PRIMESUM needs two numbers (start, end); " + file.getFileName() + " has only one.");
                return;
            }
            startField.setText(tokens.get(0));
            endField.setText(tokens.get(1));
            sourceLabel.setText("start/end from " + file.getFileName());
        } else {
            useTokens(tokens, file.getFileName().toString());
        }
        String skipped = data.getSkippedCells() == 0 ? ""
                : String.format(" (skipped %d non-numeric cell(s), e.g. %s)", data.getSkippedCells(), data.getSkippedSamples());
        log("Loaded " + tokens.size() + " number(s) from " + file + skipped);
    }

    private void generateRandom() {
        String answer = JOptionPane.showInputDialog(this,
                "How many random numbers (between 1 and " + randomMax + ")?", "1000");
        if (answer == null) {
            return;
        }
        int count;
        try {
            count = Integer.parseInt(answer.trim());
            if (count < 1 || count > config.getPositiveInt("jobs.maxListSize")) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            error("Please enter a whole number between 1 and " + config.get("jobs.maxListSize") + ".");
            return;
        }
        if (selectedType().takesRange()) {
            typeBox.setSelectedItem(JobType.PRIMECOUNT);
        }
        List<String> tokens = new ArrayList<>(count);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < count; i++) {
            tokens.add(Long.toString(rnd.nextLong(1, randomMax + 1)));
        }
        useTokens(tokens, "the random generator");
    }

    private File defaultDirectory() {
        File data = new File("data");
        return data.isDirectory() ? data : new File(".");
    }

    // ================================================================== submitting

    private void submitFromForm() {
        JobType type = selectedType();
        int copies = (Integer) copiesSpinner.getValue();
        // Capture the input on the EDT; parse it on a background thread (lists can be huge).
        List<String> tokens = type.takesRange()
                ? java.util.Arrays.asList(startField.getText().trim(), endField.getText().trim())
                : (loadedTokens != null ? loadedTokens : InputParser.tokenize(numbersArea.getText()));
        submitButton.setEnabled(false);
        ioPool.execute(() -> {
            try {
                Job job = JobFactory.create(type, tokens);
                SwingUtilities.invokeLater(() -> submitAll(Collections.nCopies(copies, job)));
            } catch (InvalidJobException e) {
                SwingUtilities.invokeLater(() -> error("Invalid input: " + e.getMessage()));
            } finally {
                SwingUtilities.invokeLater(() -> submitButton.setEnabled(true));
            }
        });
    }

    private void submitBatch() {
        JFileChooser chooser = new JFileChooser(defaultDirectory());
        chooser.setFileFilter(new FileNameExtensionFilter("CSV files", "csv", "txt"));
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path file = chooser.getSelectedFile().toPath();
        ioPool.execute(() -> {
            try {
                List<Job> jobs = CsvLoader.readBatch(file);
                SwingUtilities.invokeLater(() -> {
                    log("Batch " + file.getFileName() + ": submitting " + jobs.size() + " job(s) concurrently");
                    submitAll(jobs);
                });
            } catch (IOException | InvalidJobException e) {
                SwingUtilities.invokeLater(() -> error("Could not load the batch file:\n" + e.getMessage()));
            }
        });
    }

    /** EDT: adds a row per job and hands each job to its own pool thread. */
    private void submitAll(List<Job> jobs) {
        for (Job job : jobs) {
            JobRecord record = tableModel.add(job);
            log("Job #" + record.getNumber() + " queued: " + job.describe());
            ClusterClient current = client;
            submitPool.execute(() -> runJob(current, record));
        }
        updateSummary();
    }

    /** Runs on a pool thread: submits one job and reports back to the EDT. */
    private void runJob(ClusterClient current, JobRecord record) {
        SwingUtilities.invokeLater(() -> {
            record.running("Finding coordinator");
            tableModel.changed(record);
            updateSummary();
        });
        try {
            JobResult result = current.submit(record.getJob(), message -> SwingUtilities.invokeLater(() -> {
                record.running(message);
                tableModel.changed(record);
            }));
            SwingUtilities.invokeLater(() -> {
                record.done(result);
                tableModel.changed(record);
                log("Job #" + record.getNumber() + " done: " + result.getDescription() + " = " + result.getValueText()
                        + "  (Worker " + result.getCoordinatorId() + ", term " + result.getTerm() + ", "
                        + result.getElapsedMs() + " ms)  " + result.distributionSummary());
                updateSummary();
                showDetails();
            });
        } catch (DistriLabException e) {
            fail(record, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(record, "Cancelled");
        } catch (RuntimeException e) {
            fail(record, "Unexpected error: " + Log.describe(e));
        }
    }

    private void fail(JobRecord record, String message) {
        SwingUtilities.invokeLater(() -> {
            record.failed(message);
            tableModel.changed(record);
            log("Job #" + record.getNumber() + " FAILED: " + message);
            updateSummary();
            showDetails();
        });
    }

    // ================================================================== status & details

    private void reconnect() {
        String host = hostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
        } catch (NumberFormatException e) {
            error("The port must be a number.");
            return;
        }
        client = new ClusterClient(new BootstrapClient(host, port, config.get("bootstrap.serviceName")),
                config.getPositiveInt("client.maxSubmitAttempts"), config.getPositiveInt("client.retryDelayMs"));
        log("Using bootstrap node " + host + ":" + port);
        refreshCoordinator();
    }

    private void refreshCoordinator() {
        if (!statusRefreshRunning.compareAndSet(false, true)) {
            return;
        }
        ClusterClient current = client;
        statusPool.execute(() -> {
            try {
                CoordinatorInfo info = current.currentCoordinatorInfo();
                SwingUtilities.invokeLater(() -> coordinatorLabel.setText(info == null
                        ? "Coordinator: cluster unreachable"
                        : info.hasCoordinator()
                        ? "Coordinator: Worker " + info.getCoordinatorId() + " (term " + info.getTerm() + ", JAC "
                        + info.getCoordinator().getJac() + " when elected)"
                        : "Coordinator: none - election pending"));
            } finally {
                statusRefreshRunning.set(false);
            }
        });
    }

    private void updateSummary() {
        summaryLabel.setText(String.format("Jobs: %d running, %d done, %d failed   |   client %s   |   bootstrap %s",
                tableModel.count(JobRecord.State.RUNNING) + tableModel.count(JobRecord.State.QUEUED),
                tableModel.count(JobRecord.State.DONE), tableModel.count(JobRecord.State.FAILED),
                client.getClientId(), client.getBootstrapAddress()));
    }

    private void showDetails() {
        int viewRow = table.getSelectedRow();
        if (viewRow < 0) {
            return;
        }
        JobRecord r = tableModel.get(table.convertRowIndexToModel(viewRow));
        StringBuilder sb = new StringBuilder();
        sb.append("Job #").append(r.getNumber()).append(": ").append(r.getJob().describe()).append('\n');
        sb.append("Status: ").append(r.getState()).append(" - ").append(r.getProgress()).append("\n\n");
        if (r.getResult() != null) {
            sb.append(r.getResult().toReport());
        } else if (r.getError() != null) {
            sb.append("Error: ").append(r.getError());
        }
        detailsArea.setText(sb.toString());
        detailsArea.setCaretPosition(0);
    }

    private void log(String message) {
        appendLog(LocalTime.now().format(TIME) + "  " + message);
    }

    private void appendLog(String line) {
        logArea.append(line + "\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void error(String message) {
        JOptionPane.showMessageDialog(this, message, "DistriLab", JOptionPane.ERROR_MESSAGE);
    }
}

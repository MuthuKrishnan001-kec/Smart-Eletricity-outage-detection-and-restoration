import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

public class MainDashboard extends JFrame {
    private static final Color BACKGROUND = new Color(244, 247, 250);
    private static final Color INK = new Color(38, 52, 67);

    private final DistributionNetwork network =
            DistributionNetwork.createDemonstrationNetwork();
    private final HistoryManager historyManager = new HistoryManager();
    private final OutageDetector outageDetector = new OutageDetector();
    private final RestorationPolicy restorationPolicy =
            RestorationPolicy.withHistoryManager(historyManager);
    private final List<NetworkNode> lastAffectedNodes = new ArrayList<>();
    private SensorMonitor sensorMonitor = createSensorMonitor();
    private final NetworkCanvas canvas = new NetworkCanvas(network);
    private final StatusSummaryPanel statusSummaryPanel = new StatusSummaryPanel();
    private final JLabel statusLabel = new JLabel("Grid online | "
            + network.getNodes().size() + " nodes | "
            + network.getLines().size() + " lines");

    public MainDashboard() {
        super("Smart Electricity Outage Detection & Restoration");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1500, 900);
        setLocationRelativeTo(null);
        buildInterface();
        refreshDashboard();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new MainDashboard().setVisible(true));
    }

    private void buildInterface() {
        setLayout(new BorderLayout(12, 12));
        getContentPane().setBackground(BACKGROUND);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        JPanel toolbar = new JPanel();
        toolbar.setBackground(Color.WHITE);
        toolbar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(222, 229, 236)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        toolbar.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 10, 0));
        toolbar.add(createButton("Auto Detection", this::simulateRandomDetectionEvent));
        toolbar.add(createButton("Restore", this::autoRestore));
        toolbar.add(createButton("History", this::showHistory));
        toolbar.add(createButton("Reset Grid", this::resetGrid));
        add(toolbar, BorderLayout.NORTH);

        canvas.setBackground(new Color(250, 252, 254));
        canvas.setBorder(BorderFactory.createLineBorder(new Color(222, 229, 236)));
        canvas.setSensorMonitor(sensorMonitor);
        canvas.setAfterEdit(this::runDetection);
        add(new JScrollPane(canvas), BorderLayout.CENTER);

        JPanel sidePanel = new JPanel(new BorderLayout(0, 10));
        sidePanel.setPreferredSize(new Dimension(420, 0));
        sidePanel.setBackground(BACKGROUND);
        sidePanel.add(statusSummaryPanel, BorderLayout.CENTER);

        statusLabel.setForeground(INK);
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 2, 2, 2));
        sidePanel.add(statusLabel, BorderLayout.SOUTH);
        add(sidePanel, BorderLayout.EAST);
    }

    private JButton createButton(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void simulateRandomDetectionEvent() {
        String[] eventTypes = {
            "Storm",
            "Peak Load Overload",
            "Random Aging Fault"
        };
        int eventIndex = (int) Math.floor(Math.random() * eventTypes.length);
        simulateCityEvent(eventTypes[eventIndex]);
    }

    private void simulateCityEvent(String eventType) {
        List<NetworkLine> affectedLines;
        try {
            affectedLines = sensorMonitor.simulateCityEvent(eventType);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
            return;
        }
        runDetection();
        List<String> lineIds = new ArrayList<>();
        for (NetworkLine line : affectedLines) {
            lineIds.add(line.getId());
        }
        statusLabel.setText(eventType + " simulated on " + String.join(", ", lineIds));
    }

    private void runDetection() {
        OutageDiagnostics diagnostics;
        try {
            diagnostics = outageDetector.detectFaults(network, sensorMonitor);
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
            return;
        }
        int newFaults = 0;
        for (NetworkLine line : diagnostics.getFaultedLines()) {
            if (!restorationPolicy.isPendingFault(line)) {
                recordFaultReport(line, diagnostics.getAffectedNodes());
                newFaults++;
            }
        }
        lastAffectedNodes.clear();
        lastAffectedNodes.addAll(diagnostics.getAffectedNodes());
        refreshDashboard();
        statusLabel.setText("Detection complete | " + lastAffectedNodes.size()
                + " affected node(s) | " + newFaults + " new fault(s)");
    }

    private void autoRestore() {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            showMessage("There are no pending faults to restore.");
            return;
        }
        boolean restored;
        try {
            restored = restorationPolicy.restoreNextFault(network, outageDetector);
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
            restored = outageDetector.findAffectedNodes(network).isEmpty();
        }
        for (FaultReport report : restorationPolicy.getHistory()) {
            if (report.isRestored()) {
                sensorMonitor.clearAnomaly(report.getFailedLine().getId());
            }
        }
        lastAffectedNodes.clear();
        lastAffectedNodes.addAll(outageDetector.findAffectedNodes(network));
        refreshDashboard();
        statusLabel.setText(restored
                ? "Restoration complete | all nodes are powered"
                : "Restoration attempted | " + lastAffectedNodes.size()
                        + " node(s) remain affected");
    }

    private void resetGrid() {
        network.reset();
        restorationPolicy.reset();
        lastAffectedNodes.clear();
        sensorMonitor = createSensorMonitor();
        canvas.setSensorMonitor(sensorMonitor);
        canvas.setSelectedElement(null, null);
        refreshDashboard();
        statusLabel.setText("Grid reset | normal lines WORKING | backup lines STANDBY");
    }

    private void recordFaultReport(NetworkLine line, List<NetworkNode> affectedNodes) {
        if (restorationPolicy.isPendingFault(line)) {
            return;
        }
        FaultReport report = new FaultReport(line, orderAffectedNodes(line, affectedNodes));
        restorationPolicy.submitFault(report, network);
        lastAffectedNodes.clear();
        lastAffectedNodes.addAll(affectedNodes);
    }

    private List<NetworkNode> orderAffectedNodes(
            NetworkLine failedLine, List<NetworkNode> affectedNodes) {
        List<NetworkNode> ordered = new ArrayList<>(affectedNodes);
        ordered.sort(Comparator
                .comparingInt((NetworkNode node) -> {
                    if (node.getId().equals(failedLine.getToNodeId())) {
                        return 0;
                    }
                    if (node.getId().equals(failedLine.getFromNodeId())) {
                        return 1;
                    }
                    if (node instanceof CriticalArea) {
                        return 2;
                    }
                    if (node instanceof ConsumerArea) {
                        return 3;
                    }
                    return 4;
                })
                .thenComparing(NetworkNode::getId));
        return ordered;
    }

    private void refreshDashboard() {
        statusSummaryPanel.refreshData(network, restorationPolicy.getPendingFaultQueue());
        canvas.repaint();
    }

    private void showMessage(String message) {
        JOptionPane.showMessageDialog(this, message, "Smart Grid",
                JOptionPane.INFORMATION_MESSAGE);
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "Invalid Update",
                JOptionPane.ERROR_MESSAGE);
    }

    private void showHistory() {
        final List<String[]> events;
        try {
            events = historyManager.loadHistory();
        } catch (IOException exception) {
            showError("Could not load outage history: " + exception.getMessage());
            return;
        }

        Object[][] rows = events.toArray(new Object[0][]);
        JTable table = new JTable(new DefaultTableModel(
                rows, HistoryManager.getHeaders()) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        });
        JDialog dialog = new JDialog(this, "Outage History", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        dialog.add(new JScrollPane(table));
        dialog.setSize(900, 360);
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private SensorMonitor createSensorMonitor() {
        SensorMonitor monitor = new SensorMonitor(20, 30, network, historyManager);
        for (NetworkLine line : network.getLines()) {
            monitor.addReading(new SensorReading(line.getId(), 230, 10, 230, 10));
        }
        return monitor;
    }

}

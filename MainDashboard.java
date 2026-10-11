import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
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
        startHttpServer();
        SwingUtilities.invokeLater(() -> new MainDashboard().setVisible(true));
    }

    public static void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
            server.createContext("/api/network", exchange -> handleJsonResponse(
                    exchange, buildNetworkPayload()));
            server.createContext("/api/history", exchange -> handleJsonResponse(
                    exchange, buildHistoryPayload()));
            server.createContext("/api/teams", exchange -> handleJsonResponse(
                    exchange, buildTeamPayload()));
            server.createContext("/api/health", exchange -> handleJsonResponse(
                    exchange, "{\"status\":\"ok\",\"service\":\"smart-grid-api\"}"));
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("SmartGrid HTTP API running on http://localhost:8080");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start HTTP API server", exception);
        }
    }

    private static void handleJsonResponse(HttpExchange exchange, String jsonPayload) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            addCorsHeaders(exchange);
            exchange.sendResponseHeaders(204, -1);
            return;
        }
        addCorsHeaders(exchange);
        byte[] responseBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization");
    }

    private static String buildNetworkPayload() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        List<NetworkNode> nodes = network.getNodes();
        List<NetworkLine> lines = network.getLines();
        StringBuilder json = new StringBuilder();
        json.append("{\"nodes\":[");
        for (int i = 0; i < nodes.size(); i++) {
            NetworkNode node = nodes.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escapeJson(node.getId())).append("\",")
                .append("\"name\":\"").append(escapeJson(node.getName())).append("\",")
                .append("\"nodeType\":\"").append(escapeJson(node.getClass().getSimpleName())).append("\",")
                .append("\"powered\":").append(node.isPowered()).append(',')
                .append("\"x\":").append(nodeX(node.getId())).append(',')
                .append("\"y\":").append(nodeY(node.getId())).append(',')
                .append("\"label\":\"").append(escapeJson(node.getNodeLabel())).append("\"}");
        }
        json.append("],\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            NetworkLine line = lines.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escapeJson(line.getId())).append("\",")
                .append("\"from\":\"").append(escapeJson(line.getFromNodeId())).append("\",")
                .append("\"to\":\"").append(escapeJson(line.getToNodeId())).append("\",")
                .append("\"status\":\"").append(escapeJson(line.getStatus().name())).append("\",")
                .append("\"backup\":").append(line.isBackupLink()).append(',')
                .append("\"repairTimeMinutes\":").append(line.getRepairTimeMinutes()).append('}');
        }
        json.append("],\"summary\":{\"totalNodes\":").append(nodes.size())
            .append(",\"totalLines\":").append(lines.size()).append("}} ");
        return json.toString();
    }

    private static String buildHistoryPayload() {
        try {
            List<String[]> records = new HistoryManager().loadHistory();
            StringBuilder json = new StringBuilder();
            json.append("{\"history\":[");
            for (int i = 0; i < records.size(); i++) {
                String[] row = records.get(i);
                if (i > 0) {
                    json.append(',');
                }
                json.append("{\"timestamp\":\"").append(escapeJson(row[0])).append("\",")
                    .append("\"eventType\":\"").append(escapeJson(row[1])).append("\",")
                    .append("\"lineId\":\"").append(escapeJson(row[2])).append("\",")
                    .append("\"affectedZones\":\"").append(escapeJson(row[3])).append("\",")
                    .append("\"assignedTeam\":\"").append(escapeJson(row[4])).append("\",")
                    .append("\"status\":\"").append(escapeJson(row[5])).append("\"}");
            }
            json.append("]}");
            return json.toString();
        } catch (IOException exception) {
            return "{\"history\":[],\"error\":\"" + escapeJson(exception.getMessage()) + "\"}";
        }
    }

    private static String buildTeamPayload() {
        RestorationPolicy restorationPolicy = new RestorationPolicy();
        List<RepairTeam> teams = restorationPolicy.getRepairTeams();
        StringBuilder json = new StringBuilder();
        json.append("{\"teams\":[");
        for (int i = 0; i < teams.size(); i++) {
            RepairTeam team = teams.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escapeJson(team.getId())).append("\",")
                .append("\"name\":\"").append(escapeJson(team.getName())).append("\",")
                .append("\"depot\":\"").append(escapeJson(team.getHomeLocation())).append("\",")
                .append("\"status\":\"").append(escapeJson(team.getStatus().name())).append("\"}");
        }
        json.append("]}");
        return json.toString();
    }

    private static int nodeX(String nodeId) {
        switch (nodeId) {
            case "S1": return 150;
            case "S2": return 150;
            case "F1": return 330;
            case "F2": return 330;
            case "F3": return 750;
            case "F4": return 750;
            case "C1": return 480;
            case "C2": return 480;
            case "C3": return 640;
            case "C4": return 940;
            case "C5": return 940;
            case "H1": return 640;
            case "E1": return 750;
            case "E2": return 940;
            default: return 60;
        }
    }

    private static int nodeY(String nodeId) {
        switch (nodeId) {
            case "S1": return 120;
            case "S2": return 210;
            case "F1": return 120;
            case "F2": return 210;
            case "F3": return 120;
            case "F4": return 210;
            case "C1": return 120;
            case "C2": return 210;
            case "C3": return 270;
            case "C4": return 120;
            case "C5": return 210;
            case "H1": return 420;
            case "E1": return 420;
            case "E2": return 420;
            default: return 60;
        }
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                    break;
            }
        }
        return escaped.toString();
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

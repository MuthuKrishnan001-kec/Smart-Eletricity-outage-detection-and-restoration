import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Insets;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static volatile MainDashboard apiDashboard;
    private static final Pattern EVENT_TYPE_PATTERN =
            Pattern.compile("\"eventType\"\\s*:\\s*\"(Storm|Peak Load Overload|Random Aging Fault)\"");
    private static final Pattern LINE_ID_PATTERN =
            Pattern.compile("\"lineId\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
    private static final Pattern NODE_ID_PATTERN =
            Pattern.compile("\"nodeId\"\\s*:\\s*\"([A-Za-z0-9_-]+)\"");
    private static final Pattern VOLTAGE_PATTERN =
            Pattern.compile("\"newVoltage\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
    private static final double NORMAL_VOLTAGE = 230.0;
    private static final double VOLTAGE_DROP_THRESHOLD_PERCENT = 20.0;
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("\"newStatus\"\\s*:\\s*\"(WORKING|FAILED|STANDBY)\"");
    private static final Color BACKGROUND = new Color(246, 248, 251);
    private static final Color INK = new Color(24, 39, 61);
    private static final Color MUTED = new Color(104, 119, 139);
    private static final Color ACCENT = new Color(37, 99, 235);

    private final DistributionNetwork network =
            DistributionNetwork.createDemonstrationNetwork();
    private final HistoryManager historyManager = new HistoryManager();
    private final OutageDetector outageDetector = new OutageDetector();
    private final RestorationPolicy restorationPolicy =
            RestorationPolicy.withHistoryManager(historyManager);
    private final List<NetworkNode> lastAffectedNodes = new ArrayList<>();
    private final Map<String, Double> nodeObservedVoltages = new HashMap<>();
    private SensorMonitor sensorMonitor = createSensorMonitor();
    private final NetworkCanvas canvas = new NetworkCanvas(network);
    private final StatusSummaryPanel statusSummaryPanel = new StatusSummaryPanel();
    private final JLabel statusLabel = new JLabel("Grid online | "
            + network.getNodes().size() + " nodes | "
            + network.getLines().size() + " lines");

    public MainDashboard() {
        super("Smart Electricity Outage Detection & Restoration");
        apiDashboard = this;
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1180, 760));
        setSize(1500, 900);
        setLocationRelativeTo(null);
        buildInterface();
        refreshDashboard();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            new MainDashboard().setVisible(true);
            startHttpServer();
        });
    }

    public static void startHttpServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);
            server.createContext("/api/network", exchange -> handleDashboardRequest(
                    exchange, "GET", MainDashboard::buildNetworkPayload));
            server.createContext("/api/outages", exchange -> handleDashboardRequest(
                    exchange, "GET", MainDashboard::buildOutageQueuePayload));
            server.createContext("/api/history", exchange -> handleDashboardRequest(
                    exchange, "GET", MainDashboard::buildHistoryPayload));
            server.createContext("/api/teams", exchange -> handleDashboardRequest(
                    exchange, "GET", MainDashboard::buildTeamPayload));
            server.createContext("/api/health", exchange -> handleDashboardRequest(
                    exchange, "GET", dashboard ->
                            "{\"status\":\"ok\",\"service\":\"smart-grid-api\"}"));
            server.createContext("/api/actions/simulate-fault", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.simulateCityEventFromApi(readEventType(request))));
            server.createContext("/api/actions/run-detection", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.runDetectionCore()));
            server.createContext("/api/actions/auto-restore", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) -> {
                        dashboard.autoRestoreFromApi();
                    }));
            server.createContext("/api/actions/reset", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.resetGrid()));
            server.createContext("/api/action", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.applyManualLineEdit(readManualEdit(request))));
            server.createContext("/api/node-voltage", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.applyNodeVoltageEdit(readNodeVoltageEdit(request))));
            server.createContext("/", exchange -> {
                addCorsHeaders(exchange);
                if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
                    exchange.sendResponseHeaders(204, -1);
                    exchange.close();
                } else {
                    sendJsonResponse(exchange, 404, "{\"error\":\"Not found\"}");
                }
            });
            server.setExecutor(Executors.newCachedThreadPool());
            server.start();
            System.out.println("SmartGrid HTTP API running on http://localhost:8081");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to start HTTP API server", exception);
        }
    }

    private static final class NodeVoltageEdit {
        private final String nodeId;
        private final double voltage;

        private NodeVoltageEdit(String nodeId, double voltage) {
            this.nodeId = nodeId;
            this.voltage = voltage;
        }
    }

    private static void handleDashboardAction(
            HttpExchange exchange, DashboardAction action) throws IOException {
        handleDashboardRequest(exchange, "POST", dashboard -> {
            action.run(dashboard, exchange);
            return "{\"status\":\"ok\"}";
        });
    }

    private static void handleDashboardRequest(
            HttpExchange exchange, String expectedMethod, DashboardOperation operation)
            throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase(expectedMethod)) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }
        try {
            String jsonPayload = runOnDashboard(operation);
            sendJsonResponse(exchange, 200, jsonPayload);
        } catch (IllegalArgumentException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private static String runOnDashboard(DashboardOperation operation) throws Exception {
        MainDashboard dashboard = apiDashboard;
        if (dashboard == null) {
            throw new IllegalStateException("Dashboard is not ready.");
        }

        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(operation.run(dashboard));
            } catch (Throwable exception) {
                failure.set(exception);
            }
        });
        Throwable exception = failure.get();
        if (exception instanceof Exception) {
            throw (Exception) exception;
        }
        if (exception != null) {
            throw new IllegalStateException(exception);
        }
        return result.get();
    }

    private static String readEventType(HttpExchange exchange) throws IOException {
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = EVENT_TYPE_PATTERN.matcher(requestBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Unsupported or missing eventType.");
        }
        return matcher.group(1);
    }

    private static ManualLineEdit readManualEdit(HttpExchange exchange) throws IOException {
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher lineMatcher = LINE_ID_PATTERN.matcher(requestBody);
        Matcher voltageMatcher = VOLTAGE_PATTERN.matcher(requestBody);
        Matcher statusMatcher = STATUS_PATTERN.matcher(requestBody);
        if (!lineMatcher.find() || !voltageMatcher.find() || !statusMatcher.find()) {
            throw new IllegalArgumentException(
                    "lineId, newVoltage, and newStatus are required.");
        }
        return new ManualLineEdit(
                lineMatcher.group(1),
                Double.parseDouble(voltageMatcher.group(1)),
                LineStatus.valueOf(statusMatcher.group(1)));
    }

    private static NodeVoltageEdit readNodeVoltageEdit(HttpExchange exchange)
            throws IOException {
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher nodeMatcher = NODE_ID_PATTERN.matcher(requestBody);
        Matcher voltageMatcher = VOLTAGE_PATTERN.matcher(requestBody);
        if (!nodeMatcher.find() || !voltageMatcher.find()) {
            throw new IllegalArgumentException("nodeId and newVoltage are required.");
        }
        return new NodeVoltageEdit(
                nodeMatcher.group(1), Double.parseDouble(voltageMatcher.group(1)));
    }

    private static void sendJsonResponse(
            HttpExchange exchange, int statusCode, String jsonPayload) throws IOException {
        byte[] responseBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(responseBytes);
        } finally {
            exchange.close();
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
    }

    private String buildNetworkPayload() {
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
                .append("\"observedVoltage\":")
                .append(nodeObservedVoltages.getOrDefault(node.getId(), NORMAL_VOLTAGE))
                .append(",\"voltageEdited\":")
                .append(nodeObservedVoltages.containsKey(node.getId()))
                .append(",\"voltageAlert\":")
                .append(isNodeVoltageAlert(
                        nodeObservedVoltages.getOrDefault(node.getId(), NORMAL_VOLTAGE)))
                .append(',')
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
                .append("\"observedVoltage\":")
                .append(sensorMonitor.getReading(line.getId()) == null ? 230
                        : sensorMonitor.getReading(line.getId()).getObservedVoltage()).append(',')
                .append("\"repairTimeMinutes\":").append(line.getRepairTimeMinutes()).append('}');
        }
        json.append("],\"summary\":{\"totalNodes\":").append(nodes.size())
            .append(",\"totalLines\":").append(lines.size()).append("}} ");
        return json.toString();
    }

    private String buildHistoryPayload() throws IOException {
        List<String[]> records = historyManager.loadHistory();
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
    }

    private String buildOutageQueuePayload() {
        List<FaultReport> reports = new ArrayList<>();
        java.util.PriorityQueue<FaultReport> queue =
                restorationPolicy.getPendingFaultQueue();
        while (!queue.isEmpty()) {
            reports.add(queue.poll());
        }

        StringBuilder json = new StringBuilder("{\"outages\":[");
        for (int i = 0; i < reports.size(); i++) {
            FaultReport report = reports.get(i);
            if (i > 0) {
                json.append(',');
            }
            RepairTeam team = report.getAssignedTeam();
            NetworkLine line = report.getFailedLine();
            json.append("{\"faultId\":\"").append(escapeJson(line.getId()))
                .append("-Fault\",\"lineId\":\"").append(escapeJson(line.getId()))
                .append("\",\"priorityScore\":").append(report.getPriorityScore())
                .append(",\"affectedNodes\":[");
            List<NetworkNode> affectedNodes = report.getAffectedNodes();
            for (int nodeIndex = 0; nodeIndex < affectedNodes.size(); nodeIndex++) {
                if (nodeIndex > 0) {
                    json.append(',');
                }
                NetworkNode node = affectedNodes.get(nodeIndex);
                json.append("{\"id\":\"").append(escapeJson(node.getId()))
                    .append("\",\"name\":\"").append(escapeJson(node.getName()))
                    .append("\"}");
            }
            json.append("],\"assignedTeam\":");
            if (team == null) {
                json.append("null");
            } else {
                json.append("{\"id\":\"").append(escapeJson(team.getId()))
                    .append("\",\"name\":\"").append(escapeJson(team.getName()))
                    .append("\"}");
            }
            json.append(",\"estimatedRepairMinutes\":")
                .append(report.getEstimatedRepairTimeMinutes())
                .append(",\"status\":\"").append(escapeJson(report.getTeamStatus()))
                .append("\"}");
        }
        return json.append("]}").toString();
    }

    private String buildTeamPayload() {
        List<RepairTeam> teams = restorationPolicy.getRepairTeams();
        StringBuilder json = new StringBuilder();
        json.append("{\"teams\":[");
        for (int i = 0; i < teams.size(); i++) {
            RepairTeam team = teams.get(i);
            if (i > 0) {
                json.append(',');
            }
            FaultReport assignedReport = null;
            for (FaultReport report : restorationPolicy.getPendingFaultQueue()) {
                if (report.getAssignedTeam() == team) {
                    assignedReport = report;
                    break;
                }
            }
            json.append("{\"id\":\"").append(escapeJson(team.getId())).append("\",")
                .append("\"name\":\"").append(escapeJson(team.getName())).append("\",")
                .append("\"depot\":\"").append(escapeJson(team.getHomeLocation())).append("\",")
                .append("\"status\":\"").append(escapeJson(team.getStatus().name())).append("\",")
                .append("\"assignedFault\":");
            if (assignedReport == null) {
                json.append("null,\"estimatedRepairMinutes\":null}");
            } else {
                json.append("\"").append(escapeJson(
                        assignedReport.getFailedLine().getId())).append("\",")
                    .append("\"estimatedRepairMinutes\":")
                    .append(assignedReport.getEstimatedRepairTimeMinutes()).append('}');
            }
        }
        json.append("]}");
        return json.toString();
    }

    private static int nodeX(String nodeId) {
        switch (nodeId) {
            case "S1":
            case "S2": return 100;
            case "F1":
            case "F2":
            case "F3":
            case "F4": return 365;
            case "C1":
            case "C4":
            case "C5":
            case "C6":
            case "C8":
            case "C10":
            case "H1": return 700;
            case "C3":
            case "C2":
            case "C7":
            case "C9":
            case "C11":
            case "E1":
            case "E2": return 1000;
            default: return 60;
        }
    }

    private static int nodeY(String nodeId) {
        switch (nodeId) {
            case "S1": return 300;
            case "S2": return 730;
            case "F1": return 160;
            case "F2": return 400;
            case "F3": return 660;
            case "F4": return 900;
            case "C1":
            case "C2": return 95;
            case "C6":
            case "C7": return 235;
            case "H1":
            case "C3": return 385;
            case "C8":
            case "C9": return 525;
            case "C4":
            case "E1": return 665;
            case "C10":
            case "E2": return 805;
            case "C5":
            case "C11": return 945;
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

    @FunctionalInterface
    private interface DashboardOperation {
        String run(MainDashboard dashboard) throws Exception;
    }

    @FunctionalInterface
    private interface DashboardAction {
        void run(MainDashboard dashboard, HttpExchange exchange) throws Exception;
    }

    private static final class ManualLineEdit {
        private final String lineId;
        private final double voltage;
        private final LineStatus status;

        private ManualLineEdit(String lineId, double voltage, LineStatus status) {
            this.lineId = lineId;
            this.voltage = voltage;
            this.status = status;
        }
    }

    private void buildInterface() {
        setLayout(new BorderLayout(16, 16));
        getContentPane().setBackground(BACKGROUND);
        getRootPane().setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JPanel header = new JPanel(new BorderLayout(24, 0));
        header.setBackground(Color.WHITE);
        header.setBorder(createCardBorder(18, 22));

        JPanel branding = new JPanel(new BorderLayout(0, 4));
        branding.setOpaque(false);
        JLabel title = new JLabel("SmartGrid");
        title.setForeground(INK);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        JLabel subtitle = new JLabel("OUTAGE DETECTION  /  RESTORATION");
        subtitle.setForeground(MUTED);
        subtitle.setFont(subtitle.getFont().deriveFont(Font.BOLD, 10f));
        branding.add(title, BorderLayout.CENTER);
        branding.add(subtitle, BorderLayout.SOUTH);
        header.add(branding, BorderLayout.WEST);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        toolbar.setOpaque(false);
        toolbar.add(createButton("Detect Outage", this::simulateRandomDetectionEvent,
                ACCENT, Color.WHITE));
        toolbar.add(createButton("Restore Grid", this::autoRestore,
                new Color(226, 244, 238), new Color(20, 116, 82)));
        toolbar.add(createButton("History", this::showHistory,
                new Color(241, 245, 249), INK));
        toolbar.add(createButton("Reset", this::resetGrid,
                new Color(254, 242, 242), new Color(185, 58, 58)));
        header.add(toolbar, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel networkPanel = new JPanel(new BorderLayout(0, 12));
        networkPanel.setBackground(Color.WHITE);
        networkPanel.setBorder(createCardBorder(18, 18));
        JLabel networkTitle = new JLabel("Network topology");
        networkTitle.setForeground(INK);
        networkTitle.setFont(networkTitle.getFont().deriveFont(Font.BOLD, 16f));
        networkPanel.add(networkTitle, BorderLayout.NORTH);
        canvas.setBackground(new Color(250, 252, 254));
        canvas.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        canvas.setSensorMonitor(sensorMonitor);
        canvas.setAfterEdit(this::runDetection);
        JScrollPane canvasScrollPane = new JScrollPane(canvas);
        canvasScrollPane.setBorder(BorderFactory.createLineBorder(
                new Color(231, 236, 242)));
        canvasScrollPane.getViewport().setBackground(canvas.getBackground());
        networkPanel.add(canvasScrollPane, BorderLayout.CENTER);
        add(networkPanel, BorderLayout.CENTER);

        JPanel sidePanel = new JPanel(new BorderLayout(0, 10));
        sidePanel.setPreferredSize(new Dimension(400, 0));
        sidePanel.setBackground(BACKGROUND);
        sidePanel.add(statusSummaryPanel, BorderLayout.CENTER);

        statusLabel.setForeground(MUTED);
        statusLabel.setFont(statusLabel.getFont().deriveFont(12f));
        statusLabel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(226, 232, 240)),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        statusLabel.setBackground(Color.WHITE);
        statusLabel.setOpaque(true);
        sidePanel.add(statusLabel, BorderLayout.SOUTH);
        add(sidePanel, BorderLayout.EAST);
    }

    private javax.swing.border.Border createCardBorder(int verticalPadding,
            int horizontalPadding) {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(226, 232, 240)),
                BorderFactory.createEmptyBorder(
                        verticalPadding, horizontalPadding,
                        verticalPadding, horizontalPadding));
    }

    private JButton createButton(String text, Runnable action,
            Color background, Color foreground) {
        JButton button = new JButton(text);
        button.setFocusPainted(false);
        button.setFont(button.getFont().deriveFont(Font.BOLD, 12f));
        button.setForeground(foreground);
        button.setBackground(background);
        button.setOpaque(true);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(background),
                BorderFactory.createEmptyBorder(9, 14, 9, 14)));
        button.setMargin(new Insets(0, 0, 0, 0));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
        try {
            simulateCityEventCore(eventType);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
        }
    }

    private void simulateCityEventFromApi(String eventType) {
        simulateCityEventCore(eventType);
    }

    private void simulateCityEventCore(String eventType) {
        List<NetworkLine> affectedLines = sensorMonitor.simulateCityEvent(eventType);
        runDetectionCore();
        List<String> lineIds = new ArrayList<>();
        for (NetworkLine line : affectedLines) {
            lineIds.add(line.getId());
        }
        statusLabel.setText(eventType + " simulated on " + String.join(", ", lineIds));
    }

    private void runDetection() {
        try {
            runDetectionCore();
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
        }
    }

    private void runDetectionCore() {
        OutageDiagnostics diagnostics = outageDetector.detectFaults(network, sensorMonitor);
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
        try {
            autoRestoreCore();
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
            lastAffectedNodes.clear();
            lastAffectedNodes.addAll(outageDetector.findAffectedNodes(network));
            refreshDashboard();
        }
    }

    private void autoRestoreFromApi() {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            throw new IllegalStateException("There are no pending faults to restore.");
        }
        autoRestoreCore();
    }

    private void autoRestoreCore() {
        boolean restored = restorationPolicy.restoreNextFault(network, outageDetector);
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
        nodeObservedVoltages.clear();
        sensorMonitor = createSensorMonitor();
        canvas.setSensorMonitor(sensorMonitor);
        canvas.setSelectedElement(null, null);
        refreshDashboard();
        statusLabel.setText("Grid reset | normal lines WORKING | backup lines STANDBY");
    }

    private void applyManualLineEdit(ManualLineEdit edit) {
        if (!Double.isFinite(edit.voltage) || edit.voltage < 0 || edit.voltage > 1000) {
            throw new IllegalArgumentException("Voltage must be between 0 and 1000 volts.");
        }
        NetworkLine line = network.getLine(edit.lineId);
        if (line == null) {
            throw new IllegalArgumentException("Unknown line: " + edit.lineId);
        }
        if (edit.status == LineStatus.STANDBY && !line.isBackupLink()) {
            throw new IllegalArgumentException(
                    "Only backup lines can be placed in STANDBY.");
        }

        if (edit.status == LineStatus.FAILED) {
            line.failLine();
        } else if (edit.status == LineStatus.STANDBY) {
            if (line.getStatus() == LineStatus.FAILED) {
                line.restoreLine();
            }
            line.deactivateBackup();
        } else {
            line.restoreLine();
            if (line.isBackupLink()) {
                line.activateBackup();
            }
        }

        SensorReading reading = sensorMonitor.getReading(line.getId());
        if (reading != null) {
            sensorMonitor.addReading(new SensorReading(
                    line.getId(), reading.getNormalVoltage(), reading.getNormalCurrent(),
                    edit.voltage, reading.getObservedCurrent()));
        }
        runDetectionCore();
        statusLabel.setText("Manual update applied | " + line.getId()
                + " " + line.getStatus() + " | " + edit.voltage + " V");
    }

    private void applyNodeVoltageEdit(NodeVoltageEdit edit) {
        if (!Double.isFinite(edit.voltage) || edit.voltage < 0 || edit.voltage > 1000) {
            throw new IllegalArgumentException("Voltage must be between 0 and 1000 volts.");
        }
        NetworkNode node = network.getNode(edit.nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + edit.nodeId);
        }
        nodeObservedVoltages.put(edit.nodeId, edit.voltage);
        canvas.repaint();
        statusLabel.setText("Node voltage updated | " + edit.nodeId
                + " | " + edit.voltage + " V");
    }

    private boolean isNodeVoltageAlert(double voltage) {
        return voltage < NORMAL_VOLTAGE
                * (1 - VOLTAGE_DROP_THRESHOLD_PERCENT / 100);
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

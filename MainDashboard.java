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
import javax.swing.JTextArea;
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
    private static final Pattern REPAIR_TIME_PATTERN =
            Pattern.compile("\"repairTimeMinutes\"\\s*:\\s*(-?\\d+)(?=\\s*[,}])");
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
    private final Object apiModelLock = new Object();
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
            server.createContext("/api/network", exchange -> handleDashboardReadRequest(
                    exchange, MainDashboard::buildNetworkPayload));
            server.createContext("/api/outages", exchange -> handleDashboardReadRequest(
                    exchange, MainDashboard::buildOutageQueuePayload));
            server.createContext("/api/history", exchange -> handleDashboardReadRequest(
                    exchange, MainDashboard::buildHistoryPayload));
            server.createContext("/api/backup-approvals", exchange -> handleDashboardReadRequest(
                    exchange, MainDashboard::buildBackupApprovalsPayload));
            server.createContext("/api/teams", exchange -> handleDashboardReadRequest(
                    exchange, MainDashboard::buildTeamPayload));
            server.createContext("/api/health", exchange -> handleDashboardReadRequest(
                    exchange, dashboard ->
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
            server.createContext("/api/actions/backup-approve", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.approveBackupFromApi(readLineId(request))));
            server.createContext("/api/actions/backup-reject", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.rejectBackupFromApi(readLineId(request))));
            server.createContext("/api/actions/backup-activate", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.activateBackupFromApi(readLineId(request))));
            server.createContext("/api/actions/reset", exchange ->
                    handleDashboardAction(exchange, (dashboard, request) ->
                            dashboard.resetGrid()));
            server.createContext("/api/action", MainDashboard::handleManualLineEditRequest);
            server.createContext("/api/node-voltage", MainDashboard::handleNodeVoltageEditRequest);
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

    private static void handleDashboardReadRequest(
            HttpExchange exchange, DashboardOperation operation) throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        int responseStatus = 200;
        String responseBody;
        if (!exchange.getRequestMethod().equalsIgnoreCase("GET")) {
            responseStatus = 405;
            responseBody = "{\"error\":\"Method not allowed\"}";
        } else {
            MainDashboard dashboard = apiDashboard;
            if (dashboard == null) {
                responseStatus = 409;
                responseBody = "{\"error\":\"Dashboard is not ready.\"}";
            } else {
                try {
                    synchronized (dashboard.apiModelLock) {
                        responseBody = operation.run(dashboard);
                    }
                } catch (IllegalArgumentException exception) {
                    responseStatus = 400;
                    responseBody = "{\"error\":\""
                            + escapeJson(exception.getMessage()) + "\"}";
                } catch (IllegalStateException exception) {
                    responseStatus = 409;
                    responseBody = "{\"error\":\""
                            + escapeJson(exception.getMessage()) + "\"}";
                } catch (Exception exception) {
                    responseStatus = 500;
                    responseBody = "{\"error\":\""
                            + escapeJson(exception.getMessage()) + "\"}";
                }
            }
        }
        sendJsonResponse(exchange, responseStatus, responseBody);
    }

    private static void handleManualLineEditRequest(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        final ManualLineEdit edit;
        try {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            edit = readManualEdit(requestBody);
        } catch (IllegalArgumentException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            return;
        } catch (IOException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"Unable to read request body.\"}");
            return;
        }

        MainDashboard dashboard = apiDashboard;
        if (dashboard == null) {
            sendJsonResponse(exchange, 409, "{\"error\":\"Dashboard is not ready.\"}");
            return;
        }
        try {
            String response;
            synchronized (dashboard.apiModelLock) {
                response = dashboard.applyManualLineEditFromApi(edit);
            }
            sendJsonResponse(exchange, 200, response);
        } catch (IllegalArgumentException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (IllegalStateException exception) {
            sendJsonResponse(exchange, 409,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private static void handleNodeVoltageEditRequest(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        if (!exchange.getRequestMethod().equalsIgnoreCase("POST")) {
            sendJsonResponse(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        final NodeVoltageEdit edit;
        try {
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            edit = readNodeVoltageEdit(requestBody);
        } catch (IllegalArgumentException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
            return;
        } catch (IOException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"Unable to read request body.\"}");
            return;
        }

        MainDashboard dashboard = apiDashboard;
        if (dashboard == null) {
            sendJsonResponse(exchange, 409, "{\"error\":\"Dashboard is not ready.\"}");
            return;
        }
        try {
            String response;
            synchronized (dashboard.apiModelLock) {
                response = dashboard.applyNodeVoltageEditFromApi(edit);
            }
            sendJsonResponse(exchange, 200, response);
        } catch (IllegalArgumentException exception) {
            sendJsonResponse(exchange, 400,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (IllegalStateException exception) {
            sendJsonResponse(exchange, 409,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            sendJsonResponse(exchange, 500,
                    "{\"error\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
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
        } catch (IllegalStateException exception) {
            sendJsonResponse(exchange, 409,
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

    private static ManualLineEdit readManualEdit(String requestBody) {
        Matcher lineMatcher = LINE_ID_PATTERN.matcher(requestBody);
        Matcher voltageMatcher = VOLTAGE_PATTERN.matcher(requestBody);
        Matcher statusMatcher = STATUS_PATTERN.matcher(requestBody);
        if (!lineMatcher.find() || !voltageMatcher.find() || !statusMatcher.find()) {
            throw new IllegalArgumentException(
                    "lineId, newVoltage, and newStatus are required.");
        }
        LineStatus status = LineStatus.valueOf(statusMatcher.group(1));
        int repairTimeMinutes = 0;
        if (status == LineStatus.FAILED) {
            Matcher repairTimeMatcher = REPAIR_TIME_PATTERN.matcher(requestBody);
            if (!repairTimeMatcher.find()) {
                throw new IllegalArgumentException(
                        "A positive integer repairTimeMinutes is required for FAILED lines.");
            }
            repairTimeMinutes = RepairTimeInput.parsePositiveMinutes(
                    repairTimeMatcher.group(1));
        }
        return new ManualLineEdit(
                lineMatcher.group(1), Double.parseDouble(voltageMatcher.group(1)),
                status, repairTimeMinutes);
    }

    private static NodeVoltageEdit readNodeVoltageEdit(String requestBody) {
        Matcher nodeMatcher = NODE_ID_PATTERN.matcher(requestBody);
        Matcher voltageMatcher = VOLTAGE_PATTERN.matcher(requestBody);
        if (!nodeMatcher.find() || !voltageMatcher.find()) {
            throw new IllegalArgumentException("nodeId and newVoltage are required.");
        }
        return new NodeVoltageEdit(
                nodeMatcher.group(1), Double.parseDouble(voltageMatcher.group(1)));
    }

    private static String readLineId(HttpExchange exchange) throws IOException {
        String requestBody = new String(
                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = LINE_ID_PATTERN.matcher(requestBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException("lineId is required.");
        }
        return matcher.group(1);
    }

    private static void sendJsonResponse(
            HttpExchange exchange, int statusCode, String jsonPayload) throws IOException {
        byte[] responseBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        try {
            exchange.sendResponseHeaders(statusCode, responseBytes.length);
            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(responseBytes);
            }
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
        boolean networkRestored =
                restorationPolicy.refreshRestorationStatuses(network, outageDetector);
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
            FaultReport report = restorationPolicy.getFaultReport(line.getId());
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"").append(escapeJson(line.getId())).append("\",")
                .append("\"from\":\"").append(escapeJson(line.getFromNodeId())).append("\",")
                .append("\"to\":\"").append(escapeJson(line.getToNodeId())).append("\",")
                .append("\"status\":\"").append(escapeJson(line.getStatus().name())).append("\",")
                .append("\"backup\":").append(line.isBackupLink()).append(',')
                .append("\"approvalStatus\":\"")
                .append(line.getBackupApprovalStatus().name()).append("\",")
                .append("\"observedVoltage\":")
                .append(sensorMonitor.getReading(line.getId()) == null ? 230
                        : sensorMonitor.getReading(line.getId()).getObservedVoltage()).append(',')
                .append("\"observedCurrent\":")
                .append(sensorMonitor.getReading(line.getId()) == null ? 10
                        : sensorMonitor.getReading(line.getId()).getObservedCurrent()).append(',')
                .append("\"repairTimeMinutes\":").append(line.getRepairTimeMinutes())
                .append(",\"repairStatus\":\"")
                .append(report == null
                        ? (line.getStatus() == LineStatus.FAILED
                                ? "FAILED_UNREPORTED" : "NO_FAULT")
                        : (report.isLineRepairCompleted() ? "LINE_REPAIRED" : "REPAIR_PENDING"))
                .append("\",\"assignedTeam\":\"")
                .append(report == null || report.getAssignedTeam() == null ? "Unassigned"
                        : escapeJson(report.getAssignedTeam().getId() + " - "
                                + report.getAssignedTeam().getName()))
                .append("\",\"travelTimeMinutes\":")
                .append(report == null ? 0 : report.getTravelTimeMinutes())
                .append(",\"estimatedRestorationTimeMinutes\":")
                .append(report == null ? 0 : report.getEstimatedRestorationTimeMinutes())
                .append(",\"affectedConsumersRestored\":")
                .append(report != null && report.areAffectedConsumersRestored())
                .append(",\"networkRestored\":").append(networkRestored)
                .append(",\"affectedConsumers\":");
            appendAffectedNodes(json, report, false);
            json.append(",\"criticalFacilities\":");
            appendAffectedNodes(json, report, true);
            json.append('}');
        }
        json.append("],\"summary\":{\"totalNodes\":").append(nodes.size())
            .append(",\"totalLines\":").append(lines.size())
            .append(",\"networkRestored\":").append(networkRestored)
            .append(",\"failedLineCount\":").append(countFailedLines())
            .append(",\"disconnectedConsumers\":").append(countDisconnectedConsumers())
            .append("}} ");
        return json.toString();
    }

    private int countFailedLines() {
        int count = 0;
        for (NetworkLine line : network.getLines()) {
            if (line.getStatus() == LineStatus.FAILED) {
                count++;
            }
        }
        return count;
    }

    private int countDisconnectedConsumers() {
        int count = 0;
        for (NetworkNode node : outageDetector.findAffectedNodes(network)) {
            if (node instanceof ConsumerArea) {
                count++;
            }
        }
        return count;
    }

    private void appendAffectedNodes(
            StringBuilder json, FaultReport report, boolean criticalOnly) {
        json.append('[');
        if (report != null) {
            boolean first = true;
            for (NetworkNode node : report.getAffectedNodes()) {
                boolean include = criticalOnly
                        ? node instanceof CriticalArea : node instanceof ConsumerArea;
                if (!include) {
                    continue;
                }
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append("{\"id\":\"").append(escapeJson(node.getId()))
                    .append("\",\"name\":\"").append(escapeJson(node.getName()))
                    .append("\"}");
            }
        }
        json.append(']');
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

    private String buildBackupApprovalsPayload() {
        StringBuilder json = new StringBuilder("{\"proposals\":[");
        List<BackupLinkProposal> proposals = restorationPolicy.getBackupLinkProposals();
        for (int i = 0; i < proposals.size(); i++) {
            BackupLinkProposal proposal = proposals.get(i);
            NetworkLine line = proposal.getBackupLine();
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"lineId\":\"").append(escapeJson(line.getId()))
                .append("\",\"failedLineId\":\"")
                .append(escapeJson(proposal.getFailedLineId()))
                .append("\",\"currentStatus\":\"")
                .append(line.getStatus().name())
                .append("\",\"approvalStatus\":\"")
                .append(proposal.getApprovalStatus().name())
                .append("\",\"path\":[");
            for (int pathIndex = 0;
                    pathIndex < proposal.getRestorationPath().size(); pathIndex++) {
                if (pathIndex > 0) {
                    json.append(',');
                }
                json.append('\"').append(escapeJson(
                        proposal.getRestorationPath().get(pathIndex))).append('\"');
            }
            json.append("],\"affectedConsumers\":[");
            for (int consumerIndex = 0;
                    consumerIndex < proposal.getAffectedConsumers().size(); consumerIndex++) {
                if (consumerIndex > 0) {
                    json.append(',');
                }
                NetworkNode consumer = proposal.getAffectedConsumers().get(consumerIndex);
                json.append("{\"id\":\"").append(escapeJson(consumer.getId()))
                    .append("\",\"name\":\"").append(escapeJson(consumer.getName()))
                    .append("\"}");
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private void appendRestorationReport(StringBuilder json, FaultReport report) {
        NetworkLine line = report.getFailedLine();
        RepairTeam team = report.getAssignedTeam();
        json.append("{\"reportId\":").append(report.getReportOrder())
            .append(",\"lineId\":\"").append(escapeJson(line.getId()))
            .append("\",\"lineStatus\":\"").append(line.getStatus().name())
            .append('"');
        appendBooleanField(json, "lineRepairCompleted", report.isLineRepairCompleted());
        appendBooleanField(
                json, "affectedConsumersRestored", report.areAffectedConsumersRestored());
        appendBooleanField(json, "networkFullyRestored", report.isNetworkFullyRestored());
        json.append(",\"affectedConsumers\":[");
        boolean firstConsumer = true;
        for (NetworkNode node : report.getAffectedNodes()) {
            if (!(node instanceof ConsumerArea)) {
                continue;
            }
            if (!firstConsumer) {
                json.append(',');
            }
            firstConsumer = false;
            json.append("{\"id\":\"").append(escapeJson(node.getId()))
                .append("\",\"name\":\"").append(escapeJson(node.getName()))
                .append("\"}");
        }
        json.append("],\"criticalFacilities\":[");
        boolean firstCritical = true;
        for (NetworkNode node : report.getAffectedNodes()) {
            if (!(node instanceof CriticalArea)) {
                continue;
            }
            if (!firstCritical) {
                json.append(',');
            }
            firstCritical = false;
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
        json.append(",\"repairTimeMinutes\":").append(report.getRepairTimeMinutes())
            .append(",\"travelTimeMinutes\":").append(report.getTravelTimeMinutes())
            .append(",\"estimatedRestorationTimeMinutes\":")
            .append(report.getEstimatedRestorationTimeMinutes()).append('}');
    }

    private String buildOutageQueuePayload() {
        restorationPolicy.refreshRestorationStatuses(network, outageDetector);
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
            json.append("{\"reportId\":").append(report.getReportOrder())
                .append(",\"faultId\":\"").append(escapeJson(line.getId()))
                .append("-Fault\",\"lineId\":\"").append(escapeJson(line.getId()))
                .append("\",\"lineStatus\":\"").append(line.getStatus().name())
                .append("\",\"lineRepairStatus\":\"")
                .append(report.isLineRepairCompleted() ? "LINE_REPAIRED" : "REPAIR_PENDING")
                .append('"');
            appendBooleanField(
                    json, "affectedConsumersRestored", report.areAffectedConsumersRestored());
            appendBooleanField(json, "networkFullyRestored", report.isNetworkFullyRestored());
            json.append(",\"priorityScore\":").append(report.getPriorityScore())
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
            json.append("],\"affectedConsumers\":");
            appendAffectedNodes(json, report, false);
            json.append(",\"criticalFacilities\":");
            appendAffectedNodes(json, report, true);
            json.append(",\"assignedTeam\":");
            if (team == null) {
                json.append("null");
            } else {
                json.append("{\"id\":\"").append(escapeJson(team.getId()))
                    .append("\",\"name\":\"").append(escapeJson(team.getName()))
                    .append("\"}");
            }
            json.append(",\"travelTimeMinutes\":")
                .append(report.getTravelTimeMinutes())
                .append(",\"repairTimeMinutes\":")
                .append(report.getRepairTimeMinutes())
                .append(",\"estimatedRestorationTimeMinutes\":")
                .append(report.getEstimatedRestorationTimeMinutes())
                .append(",\"estimatedRepairMinutes\":")
                .append(report.getEstimatedRestorationTimeMinutes())
                .append(",\"status\":\"").append(escapeJson(report.getTeamStatus()))
                .append("\"}");
        }
        json.append("],\"restorationReports\":[");
        List<FaultReport> allReports = restorationPolicy.getFaultReports();
        for (int index = 0; index < allReports.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            appendRestorationReport(json, allReports.get(index));
        }
        return json.append("]}").toString();
    }

    private static void appendBooleanField(StringBuilder json, String fieldName, boolean value) {
        json.append(",\"").append(fieldName).append("\":").append(value);
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
                json.append("null,\"travelTimeMinutes\":null,\"repairTimeMinutes\":null,")
                    .append("\"estimatedRestorationTimeMinutes\":null,")
                    .append("\"estimatedRepairMinutes\":null}");
            } else {
                json.append("\"").append(escapeJson(
                        assignedReport.getFailedLine().getId())).append("\",")
                    .append("\"travelTimeMinutes\":")
                    .append(assignedReport.getTravelTimeMinutes()).append(',')
                    .append("\"repairTimeMinutes\":")
                    .append(assignedReport.getRepairTimeMinutes()).append(',')
                    .append("\"estimatedRestorationTimeMinutes\":")
                    .append(assignedReport.getEstimatedRestorationTimeMinutes()).append(',')
                    .append("\"estimatedRepairMinutes\":")
                    .append(assignedReport.getEstimatedRestorationTimeMinutes()).append('}');
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
        private final int repairTimeMinutes;

        private ManualLineEdit(
                String lineId, double voltage, LineStatus status, int repairTimeMinutes) {
            this.lineId = lineId;
            this.voltage = voltage;
            this.status = status;
            this.repairTimeMinutes = repairTimeMinutes;
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
        toolbar.add(createButton("Backup Links", this::showBackupApprovals,
                new Color(232, 240, 255), new Color(37, 83, 160)));
        toolbar.add(createButton("History", this::showHistory,
                new Color(241, 245, 249), INK));
        toolbar.add(createButton("Reset", this::resetGrid,
                new Color(254, 242, 242), new Color(185, 58, 58)));
        header.add(toolbar, BorderLayout.EAST);
        JPanel headerContainer = new JPanel(new BorderLayout(0, 10));
        headerContainer.setOpaque(false);
        headerContainer.add(header, BorderLayout.NORTH);
        add(headerContainer, BorderLayout.NORTH);

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
        canvas.setAfterEdit(this::runDetectionAfterEdit);
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
            simulateCityEventCore(eventType, true);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            showError(exception.getMessage());
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
        }
    }

    private void simulateCityEventFromApi(String eventType) {
        simulateCityEventCore(eventType, false);
    }

    private void simulateCityEventCore(String eventType, boolean promptForRepairTime) {
        List<NetworkLine> affectedLines = sensorMonitor.simulateCityEvent(eventType);
        runDetectionCore(null, promptForRepairTime);
        List<String> lineIds = new ArrayList<>();
        for (NetworkLine line : affectedLines) {
            lineIds.add(line.getId());
        }
        statusLabel.setText(eventType + " simulated on " + String.join(", ", lineIds));
    }

    private void runDetection() {
        runDetection(null);
    }

    private void runDetectionAfterEdit(NetworkLine editedLine) {
        try {
            runDetectionCore(
                    editedLine != null && editedLine.getStatus() == LineStatus.FAILED
                            ? editedLine.getId() : null,
                    false);
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
        }
    }

    private void runDetection(String lineWithOperatorEnteredRepairTime) {
        try {
            runDetectionCore(lineWithOperatorEnteredRepairTime);
        } catch (UncheckedIOException exception) {
            showError(exception.getMessage());
        }
    }

    private void runDetectionCore() {
        runDetectionCore(null, false);
    }

    private void runDetectionCore(String lineWithOperatorEnteredRepairTime) {
        runDetectionCore(lineWithOperatorEnteredRepairTime, true);
    }

    private void runDetectionCore(
            String lineWithOperatorEnteredRepairTime, boolean promptForRepairTime) {
        try {
            int newFaults = runDetectionModel(
                    lineWithOperatorEnteredRepairTime, promptForRepairTime);
            refreshDashboard();
            statusLabel.setText("Detection complete | " + lastAffectedNodes.size()
                    + " affected node(s) | " + newFaults + " new fault(s)");
        } catch (UncheckedIOException exception) {
            refreshDashboard();
            statusLabel.setText("Faults queued; outage history could not be saved");
            throw exception;
        }
    }

    private int runDetectionModel(
            String lineWithOperatorEnteredRepairTime, boolean promptForRepairTime) {
        OutageDiagnostics diagnostics = outageDetector.detectFaults(network, sensorMonitor);
        List<UncheckedIOException> historyFailures =
                sensorMonitor.drainHistoryWriteFailures();
        int newFaults = 0;
        for (NetworkLine line : diagnostics.getFaultedLines()) {
            if (!restorationPolicy.isPendingFault(line)) {
                if (!line.getId().equals(lineWithOperatorEnteredRepairTime)
                        && promptForRepairTime) {
                    Integer repairMinutes = promptRepairTime(line);
                    if (repairMinutes == null) {
                        line.restoreLine();
                        sensorMonitor.clearAnomaly(line.getId());
                        continue;
                    }
                    line.setRepairTimeMinutes(repairMinutes);
                }
                try {
                    recordFaultReport(line, diagnostics.getAffectedNodes());
                    newFaults++;
                } catch (UncheckedIOException exception) {
                    if (restorationPolicy.isPendingFault(line)) {
                        newFaults++;
                    }
                    historyFailures.add(exception);
                }
            }
        }
        lastAffectedNodes.clear();
        lastAffectedNodes.addAll(outageDetector.findAffectedNodes(network));
        if (!historyFailures.isEmpty()) {
            IOException cause = historyFailures.get(0).getCause();
            for (int index = 1; index < historyFailures.size(); index++) {
                cause.addSuppressed(historyFailures.get(index).getCause());
            }
            throw new UncheckedIOException(
                    "Faults were detected and queued, but history could not be saved. "
                            + "Close outage_history.csv in Excel or another application "
                            + "and retry detection.",
                    cause);
        }
        return newFaults;
    }

    private void autoRestore() {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            showMessage("There are no pending faults to restore.");
            return;
        }
        if (restorationPolicy.hasPendingBackupApprovalDecision()) {
            showMessage("Review each proposed backup link in Backup Links before continuing repair.");
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
        if (restorationPolicy.hasPendingBackupApprovalDecision()) {
            throw new IllegalStateException(
                    "Review each proposed backup link before continuing repair.");
        }
        autoRestoreCore();
    }

    private void approveBackupFromApi(String lineId) {
        restorationPolicy.approveBackupLink(lineId);
        refreshDashboard();
    }

    private void rejectBackupFromApi(String lineId) {
        restorationPolicy.rejectBackupLink(lineId);
        refreshDashboard();
    }

    private void activateBackupFromApi(String lineId) {
        int affectedCount = restorationPolicy.activateApprovedBackupLink(
                lineId, network, outageDetector);
        lastAffectedNodes.clear();
        lastAffectedNodes.addAll(outageDetector.findAffectedNodes(network));
        statusLabel.setText("Backup BFS verified | " + affectedCount
                + " affected consumer(s) remain");
        refreshDashboard();
    }

    private void autoRestoreCore() {
        boolean restored = restorationPolicy.restoreNextFault(network, outageDetector);
        for (FaultReport report : restorationPolicy.getHistory()) {
            if (report.isLineRepairCompleted()) {
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

    private String applyManualLineEditFromApi(ManualLineEdit edit) {
        if (!Double.isFinite(edit.voltage) || edit.voltage <= 0 || edit.voltage > 1000) {
            throw new IllegalArgumentException(
                    "Voltage must be a positive number no greater than 1000 volts.");
        }
        NetworkLine line = network.getLine(edit.lineId);
        if (line == null) {
            throw new IllegalArgumentException("Unknown line: " + edit.lineId);
        }
        boolean managedPrimaryRepair = edit.status == LineStatus.WORKING
                && !line.isBackupLink() && line.getStatus() == LineStatus.FAILED
                && restorationPolicy.isPendingFault(line);
        if (managedPrimaryRepair) {
            restorationPolicy.restoreFault(line.getId(), network, outageDetector);
            sensorMonitor.clearAnomaly(line.getId());
        }
        if (edit.status == LineStatus.STANDBY && !line.isBackupLink()) {
            throw new IllegalArgumentException(
                    "Only backup lines can be placed in STANDBY.");
        }

        if (line.isBackupLink() && edit.status == LineStatus.WORKING
                && line.getBackupApprovalStatus() != BackupApprovalStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Backup links must be approved and activated through Backup Links.");
        }

        if (edit.status == LineStatus.FAILED) {
            if (!line.isWorking() && line.getStatus() != LineStatus.FAILED) {
                throw new IllegalStateException(
                        "Only working or already-failed lines can be marked FAILED.");
            }
            line.failLine(edit.repairTimeMinutes);
        } else if (edit.status == LineStatus.STANDBY) {
            if (line.getStatus() == LineStatus.FAILED) {
                line.restoreLine();
            }
            line.deactivateBackup();
        } else if (!managedPrimaryRepair) {
            if (!line.isBackupLink()) {
                line.restoreLine();
            }
        }

        SensorReading reading = sensorMonitor.getReading(line.getId());
        if (reading != null) {
            sensorMonitor.addReading(new SensorReading(
                    line.getId(), reading.getNormalVoltage(), reading.getNormalCurrent(),
                    edit.voltage, reading.getObservedCurrent()));
        }
        runDetectionModel(edit.status == LineStatus.FAILED ? line.getId() : null, false);
        String message = "Manual update applied | " + line.getId()
                + " " + line.getStatus() + " | " + edit.voltage + " V"
                + (line.getStatus() == LineStatus.FAILED
                        ? " | repair " + line.getRepairTimeMinutes() + " min" : "");
        SwingUtilities.invokeLater(() -> {
            synchronized (apiModelLock) {
                refreshDashboard();
                statusLabel.setText(message);
            }
        });
        return manualLineEditResponse(line.getId());
    }

    private String manualLineEditResponse(String lineId) {
        NetworkLine line = network.getLine(lineId);
        if (line == null) {
            throw new IllegalStateException("Updated line is no longer available: " + lineId);
        }
        SensorReading reading = sensorMonitor.getReading(lineId);
        double observedVoltage = reading == null
                ? NORMAL_VOLTAGE : reading.getObservedVoltage();
        return "{\"status\":\"ok\",\"line\":{\"id\":\""
                + escapeJson(line.getId()) + "\",\"status\":\""
                + line.getStatus().name() + "\",\"observedVoltage\":"
                + observedVoltage + ",\"repairTimeMinutes\":"
                + line.getRepairTimeMinutes() + "}}";
    }

    private String nodeVoltageEditResponse(String nodeId, int detectedFaultCount) {
        NetworkNode node = network.getNode(nodeId);
        Double observedVoltage = nodeObservedVoltages.get(nodeId);
        if (node == null || observedVoltage == null) {
            throw new IllegalStateException("Updated node is no longer available: " + nodeId);
        }
        return "{\"status\":\"ok\",\"node\":{\"id\":\""
                + escapeJson(node.getId()) + "\",\"observedVoltage\":"
                + observedVoltage + "},\"detectedFaultCount\":"
                + detectedFaultCount + "}";
    }

    private String applyNodeVoltageEditFromApi(NodeVoltageEdit edit) {
        if (!Double.isFinite(edit.voltage) || edit.voltage < 0 || edit.voltage > 1000) {
            throw new IllegalArgumentException("Voltage must be between 0 and 1000 volts.");
        }
        NetworkNode node = network.getNode(edit.nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + edit.nodeId);
        }
        nodeObservedVoltages.put(edit.nodeId, edit.voltage);
        sensorMonitor.updateIncidentLineVoltage(edit.nodeId, edit.voltage);
        int detectedFaultCount = runDetectionModel(null, false);
        SwingUtilities.invokeLater(() -> {
            synchronized (apiModelLock) {
                refreshDashboard();
                statusLabel.setText("Node voltage updated | " + edit.nodeId
                        + " | " + edit.voltage + " V | "
                        + detectedFaultCount + " new fault(s)");
            }
        });
        return nodeVoltageEditResponse(edit.nodeId, detectedFaultCount);
    }

    private boolean isNodeVoltageAlert(double voltage) {
        return SensorReading.isVoltageAbnormal(
                voltage, NORMAL_VOLTAGE, VOLTAGE_DROP_THRESHOLD_PERCENT);
    }

    private Integer promptRepairTime(NetworkLine line) {
        while (true) {
            String value = JOptionPane.showInputDialog(this,
                    "Enter estimated repair time for " + line.getId()
                            + " in positive whole minutes:",
                    Integer.toString(line.getRepairTimeMinutes()));
            if (value == null) {
                return null;
            }
            try {
                return RepairTimeInput.parsePositiveMinutes(value);
            } catch (IllegalArgumentException exception) {
                showError(exception.getMessage());
            }
        }
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
        statusSummaryPanel.refreshData(network, restorationPolicy, outageDetector);
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

    private void showBackupApprovals() {
        DefaultTableModel model = new DefaultTableModel(
                new Object[] {"Backup line", "Current status", "Proposed path",
                    "Approval status", "Affected consumers"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        JButton approveButton = createButton("Approve", () -> { },
                new Color(226, 244, 238), new Color(20, 116, 82));
        JButton rejectButton = createButton("Reject", () -> { },
                new Color(254, 242, 242), new Color(185, 58, 58));
        JButton activateButton = createButton("Activate approved link", () -> { },
                new Color(232, 240, 255), new Color(37, 83, 160));
        JButton closeButton = createButton("Close", () -> { },
                new Color(241, 245, 249), INK);

        JDialog dialog = new JDialog(this, "Backup Link Approval", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(new JScrollPane(table), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(approveButton);
        actions.add(rejectButton);
        actions.add(activateButton);
        actions.add(closeButton);
        content.add(actions, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.setSize(1050, 380);
        dialog.setLocationRelativeTo(this);

        Runnable refillRows = () -> {
            model.setRowCount(0);
            for (BackupLinkProposal proposal : restorationPolicy.getBackupLinkProposals()) {
                String consumers = proposal.getAffectedConsumers().stream()
                        .map(node -> node.getId() + " (" + node.getName() + ")")
                        .collect(java.util.stream.Collectors.joining(", "));
                model.addRow(new Object[] {
                    proposal.getBackupLineId(), proposal.getBackupLine().getStatus(),
                    String.join(" -> ", proposal.getRestorationPath()),
                    proposal.getApprovalStatus(), consumers
                });
            }
        };
        Runnable updateActionStates = () -> {
            int row = table.getSelectedRow();
            boolean selected = row >= 0;
            String approval = selected
                    ? String.valueOf(model.getValueAt(row, 3)) : "";
            approveButton.setEnabled("PROPOSED".equals(approval));
            rejectButton.setEnabled("PROPOSED".equals(approval)
                    || "APPROVED".equals(approval));
            activateButton.setEnabled("APPROVED".equals(approval));
        };
        java.util.function.Supplier<BackupLinkProposal> selectedProposal = () -> {
            int row = table.getSelectedRow();
            if (row < 0) {
                throw new IllegalStateException("Select a backup proposal first.");
            }
            String lineId = String.valueOf(model.getValueAt(row, 0));
            for (BackupLinkProposal proposal : restorationPolicy.getBackupLinkProposals()) {
                if (proposal.getBackupLineId().equals(lineId)) {
                    return proposal;
                }
            }
            throw new IllegalStateException("The selected backup proposal is no longer current.");
        };
        table.getSelectionModel().addListSelectionListener(event -> updateActionStates.run());
        approveButton.addActionListener(event -> {
            try {
                BackupLinkProposal proposal = selectedProposal.get();
                restorationPolicy.approveBackupLink(proposal.getBackupLineId());
                statusLabel.setText(proposal.getBackupLineId() + " approved; awaiting activation.");
                refillRows.run();
                updateActionStates.run();
                refreshDashboard();
            } catch (RuntimeException exception) {
                showError(exception.getMessage());
            }
        });
        rejectButton.addActionListener(event -> {
            try {
                BackupLinkProposal proposal = selectedProposal.get();
                restorationPolicy.rejectBackupLink(proposal.getBackupLineId());
                statusLabel.setText(proposal.getBackupLineId() + " rejected and remains inactive.");
                refillRows.run();
                updateActionStates.run();
                refreshDashboard();
            } catch (RuntimeException exception) {
                showError(exception.getMessage());
            }
        });
        activateButton.addActionListener(event -> {
            try {
                BackupLinkProposal proposal = selectedProposal.get();
                int affected = restorationPolicy.activateApprovedBackupLink(
                        proposal.getBackupLineId(), network, outageDetector);
                lastAffectedNodes.clear();
                lastAffectedNodes.addAll(outageDetector.findAffectedNodes(network));
                statusLabel.setText("Backup BFS verified | " + affected
                        + " affected consumer(s) remain");
                refillRows.run();
                updateActionStates.run();
                refreshDashboard();
            } catch (RuntimeException exception) {
                showError(exception.getMessage());
            }
        });
        closeButton.addActionListener(event -> dialog.dispose());

        refillRows.run();
        updateActionStates.run();
        dialog.setVisible(true);
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebServer {
    private static final int PORT = 8081;
    private static final double NORMAL_VOLTAGE = 230.0;
    private static final double VOLTAGE_DROP_THRESHOLD_PERCENT = 20.0;

    private final DistributionNetwork network;
    private final OutageDetector outageDetector = new OutageDetector();
    private final RestorationPolicy restorationPolicy;
    private final HistoryManager historyManager;
    private final Map<String, Double> nodeObservedVoltages = new HashMap<>();
    private SensorMonitor sensorMonitor;

    private WebServer() {
        this(Paths.get("outage_history.csv"));
    }

    private WebServer(Path historyFile) {
        network = DistributionNetwork.createDemonstrationNetwork();
        historyManager = new HistoryManager(historyFile);
        restorationPolicy = RestorationPolicy.withHistoryManager(historyManager);
        sensorMonitor = createSensorMonitor();
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : PORT;
        Path historyFile = args.length > 1
                ? Paths.get(args[1]) : Paths.get("outage_history.csv");
        WebServer application = new WebServer(historyFile);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", application::handleRequest);
        server.start();
        System.out.println("SmartGrid HTTP API running on http://localhost:" + port);
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);
        String method = exchange.getRequestMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        String path = exchange.getRequestURI().getPath();
        int responseStatus = 200;
        String responseBody;
        try {
            if ("POST".equalsIgnoreCase(method)) {
                String requestBody = readRequestBody(exchange);
                responseBody = "{\"status\":\"ok\"}";
                switch (path) {
                    case "/api/actions/backup-approve":
                        restorationPolicy.approveBackupLink(readLineId(requestBody));
                        break;
                    case "/api/actions/backup-reject":
                        restorationPolicy.rejectBackupLink(readLineId(requestBody));
                        break;
                    case "/api/actions/backup-activate":
                        restorationPolicy.activateApprovedBackupLink(
                                readLineId(requestBody), network, outageDetector);
                        break;
                    case "/api/actions/simulate-fault":
                        simulateCityEvent(readStringField(requestBody, "eventType"));
                        break;
                    case "/api/actions/run-detection":
                        detectAndSubmitFaults();
                        break;
                    case "/api/actions/auto-restore":
                        restoreNextFault();
                        break;
                    case "/api/actions/reset":
                        resetNetwork();
                        break;
                    case "/api/action":
                        responseBody = applyManualLineEdit(readManualLineEdit(requestBody));
                        break;
                    case "/api/node-voltage":
                        responseBody = applyNodeVoltageEdit(requestBody);
                        break;
                    default:
                        responseStatus = isGetEndpoint(path) ? 405 : 404;
                        responseBody = isGetEndpoint(path)
                                ? "{\"error\":\"Method not allowed\"}"
                                : "{\"error\":\"Not found\"}";
                }
            } else if (!"GET".equalsIgnoreCase(method)) {
                responseStatus = 405;
                responseBody = "{\"error\":\"Method not allowed\"}";
            } else {
                switch (path) {
                    case "/api/network":
                        responseBody = networkPayload();
                        break;
                    case "/api/backup-approvals":
                        responseBody = backupApprovalsPayload();
                        break;
                    case "/api/teams":
                        responseBody = teamsPayload();
                        break;
                    case "/api/queue":
                    case "/api/outages":
                        responseBody = queuePayload();
                        break;
                    case "/api/history":
                        responseBody = historyPayload();
                        break;
                    case "/api/health":
                        responseBody = "{\"status\":\"ok\",\"service\":\"smart-grid-api\"}";
                        break;
                    default:
                        responseStatus = isPostEndpoint(path) ? 405 : 404;
                        responseBody = isPostEndpoint(path)
                                ? "{\"error\":\"Method not allowed\"}"
                                : "{\"error\":\"Not found\"}";
                        break;
                }
            }
        } catch (IllegalArgumentException exception) {
            responseStatus = 400;
            responseBody = "{\"error\":" + jsonString(exception.getMessage()) + "}";
        } catch (IllegalStateException exception) {
            responseStatus = 409;
            responseBody = "{\"error\":" + jsonString(exception.getMessage()) + "}";
        } catch (IOException exception) {
            responseStatus = 500;
            responseBody = "{\"error\":" + jsonString(exception.getMessage()) + "}";
        } catch (RuntimeException exception) {
            responseStatus = 500;
            responseBody = "{\"error\":" + jsonString(exception.getMessage()) + "}";
        }
        sendJson(exchange, responseStatus, responseBody);
    }

    private String networkPayload() {
        boolean networkRestored = restorationPolicy.refreshRestorationStatuses(
                network, outageDetector);
        List<NetworkNode> nodes = network.getNodes();
        List<NetworkLine> lines = network.getLines();
        StringBuilder json = new StringBuilder("{\"nodes\":[");
        for (int index = 0; index < nodes.size(); index++) {
            NetworkNode node = nodes.get(index);
            if (index > 0) {
                json.append(',');
            }
            int[] position = nodePosition(node.getId());
            json.append("{\"id\":").append(jsonString(node.getId()))
                    .append(",\"name\":").append(jsonString(node.getName()))
                    .append(",\"nodeType\":").append(jsonString(
                            node.getClass().getSimpleName()))
                    .append(",\"powered\":").append(node.isPowered())
                    .append(",\"observedVoltage\":")
                    .append(nodeObservedVoltages.getOrDefault(node.getId(), NORMAL_VOLTAGE))
                    .append(",\"voltageEdited\":")
                    .append(nodeObservedVoltages.containsKey(node.getId()))
                    .append(",\"voltageAlert\":")
                    .append(isNodeVoltageAlert(
                            nodeObservedVoltages.getOrDefault(node.getId(), NORMAL_VOLTAGE)))
                    .append(",\"x\":").append(position[0])
                    .append(",\"y\":").append(position[1])
                    .append(",\"label\":").append(jsonString(node.getNodeLabel()))
                    .append('}');
        }

        json.append("],\"lines\":[");
        for (int index = 0; index < lines.size(); index++) {
            NetworkLine line = lines.get(index);
            FaultReport report = restorationPolicy.getFaultReport(line.getId());
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(jsonString(line.getId()))
                    .append(",\"from\":").append(jsonString(line.getFromNodeId()))
                    .append(",\"to\":").append(jsonString(line.getToNodeId()))
                    .append(",\"status\":").append(jsonString(line.getStatus().name()))
                    .append(",\"backup\":").append(line.isBackupLink())
                    .append(",\"approvalStatus\":")
                    .append(jsonString(line.getBackupApprovalStatus().name()))
                    .append(",\"observedVoltage\":")
                    .append(sensorMonitor.getReading(line.getId()) == null ? NORMAL_VOLTAGE
                            : sensorMonitor.getReading(line.getId()).getObservedVoltage())
                    .append(",\"observedCurrent\":")
                    .append(sensorMonitor.getReading(line.getId()) == null ? 10
                            : sensorMonitor.getReading(line.getId()).getObservedCurrent())
                    .append(",\"repairTimeMinutes\":").append(line.getRepairTimeMinutes())
                    .append(",\"repairStatus\":")
                    .append(jsonString(report == null
                            ? (line.getStatus() == LineStatus.FAILED
                                    ? "FAILED_UNREPORTED" : "NO_FAULT")
                            : (report.isLineRepairCompleted()
                                    ? "LINE_REPAIRED" : "REPAIR_PENDING")))
                    .append(",\"assignedTeam\":")
                    .append(jsonString(report == null || report.getAssignedTeam() == null
                            ? "Unassigned" : report.getAssignedTeam().getId() + " - "
                                    + report.getAssignedTeam().getName()))
                    .append(",\"travelTimeMinutes\":")
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
            json
                    .append('}');
        }
        return json.append("],\"summary\":{\"totalNodes\":")
                .append(nodes.size())
                .append(",\"totalLines\":").append(lines.size())
                .append(",\"networkRestored\":").append(networkRestored)
                .append(",\"failedLineCount\":").append(countFailedLines())
                .append(",\"disconnectedConsumers\":").append(countDisconnectedConsumers())
                .append("}}").toString();
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

    private static void appendAffectedNodes(
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
                json.append("{\"id\":").append(jsonString(node.getId()))
                        .append(",\"name\":").append(jsonString(node.getName())).append('}');
            }
        }
        json.append(']');
    }

    private SensorMonitor createSensorMonitor() {
        SensorMonitor monitor = new SensorMonitor(20, 30, network, historyManager);
        for (NetworkLine line : network.getLines()) {
            monitor.addReading(new SensorReading(line.getId(), NORMAL_VOLTAGE, 10,
                    NORMAL_VOLTAGE, 10));
        }
        return monitor;
    }

    private static String readRequestBody(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String readStringField(String requestBody, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName)
                + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"");
        Matcher matcher = pattern.matcher(requestBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        return matcher.group(1);
    }

    private static double readNumberField(String requestBody, String fieldName) {
        Pattern pattern = Pattern.compile("\\\"" + Pattern.quote(fieldName)
                + "\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))");
        Matcher matcher = pattern.matcher(requestBody);
        if (!matcher.find()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(fieldName + " must be a number.", exception);
        }
    }

    private static String readLineId(String requestBody) {
        return readStringField(requestBody, "lineId");
    }

    private static ManualLineEdit readManualLineEdit(String requestBody) {
        String lineId = readLineId(requestBody);
        double voltage = readNumberField(requestBody, "newVoltage");
        LineStatus status;
        try {
            status = LineStatus.valueOf(readStringField(requestBody, "newStatus"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "newStatus must be WORKING, FAILED, or STANDBY.", exception);
        }
        int repairTimeMinutes = 0;
        if (status == LineStatus.FAILED) {
            double value = readNumberField(requestBody, "repairTimeMinutes");
            if (value != Math.rint(value) || value > Integer.MAX_VALUE || value < 1) {
                throw new IllegalArgumentException(
                        "repairTimeMinutes must be a positive whole number of minutes.");
            }
            repairTimeMinutes = (int) value;
        }
        return new ManualLineEdit(lineId, voltage, status, repairTimeMinutes);
    }

    private void simulateCityEvent(String eventType) {
        sensorMonitor.simulateCityEvent(eventType);
        detectAndSubmitFaults();
    }

    private int detectAndSubmitFaults() {
        List<NetworkLine> faultedLines = new ArrayList<>(
                sensorMonitor.findFaultedLines(network.getLines()));
        List<NetworkNode> affectedNodes = outageDetector.findAffectedNodes(network);
        List<UncheckedIOException> historyFailures =
                sensorMonitor.drainHistoryWriteFailures();
        int newFaults = 0;
        for (NetworkLine line : faultedLines) {
            if (restorationPolicy.isPendingFault(line)) {
                continue;
            }
            try {
                restorationPolicy.submitFault(
                        new FaultReport(line, orderAffectedNodes(line, affectedNodes)), network);
                newFaults++;
            } catch (UncheckedIOException exception) {
                if (restorationPolicy.isPendingFault(line)) {
                    newFaults++;
                }
                historyFailures.add(exception);
            }
        }
        restorationPolicy.refreshRestorationStatuses(network, outageDetector);
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

    private List<NetworkNode> orderAffectedNodes(
            NetworkLine failedLine, List<NetworkNode> affectedNodes) {
        List<NetworkNode> ordered = new ArrayList<>(affectedNodes);
        ordered.sort(java.util.Comparator
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

    private String applyManualLineEdit(ManualLineEdit edit) {
        if (!Double.isFinite(edit.voltage) || edit.voltage <= 0 || edit.voltage > 1000) {
            throw new IllegalArgumentException(
                    "Voltage must be a positive number no greater than 1000 volts.");
        }
        NetworkLine line = network.getLine(edit.lineId);
        if (line == null) {
            throw new IllegalArgumentException("Unknown line: " + edit.lineId);
        }
        if (edit.status == LineStatus.STANDBY && !line.isBackupLink()) {
            throw new IllegalArgumentException("Only backup lines can be placed in STANDBY.");
        }
        if (line.isBackupLink() && edit.status == LineStatus.WORKING
                && line.getBackupApprovalStatus() != BackupApprovalStatus.ACTIVE) {
            throw new IllegalArgumentException(
                    "Backup links must be approved and activated through Backup Links.");
        }

        boolean managedPrimaryRepair = edit.status == LineStatus.WORKING
                && !line.isBackupLink() && line.getStatus() == LineStatus.FAILED
                && restorationPolicy.isPendingFault(line);
        if (managedPrimaryRepair) {
            restorationPolicy.restoreFault(line.getId(), network, outageDetector);
            sensorMonitor.clearAnomaly(line.getId());
        } else if (edit.status == LineStatus.FAILED) {
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
        } else if (!managedPrimaryRepair && !line.isBackupLink()) {
            line.restoreLine();
        }

        SensorReading reading = sensorMonitor.getReading(line.getId());
        if (reading != null) {
            sensorMonitor.addReading(new SensorReading(
                    line.getId(), reading.getNormalVoltage(), reading.getNormalCurrent(),
                    edit.voltage, reading.getObservedCurrent()));
        }
        detectAndSubmitFaults();
        SensorReading updatedReading = sensorMonitor.getReading(line.getId());
        double observedVoltage = updatedReading == null
                ? NORMAL_VOLTAGE : updatedReading.getObservedVoltage();
        return "{\"status\":\"ok\",\"line\":{\"id\":"
                + jsonString(line.getId()) + ",\"status\":"
                + jsonString(line.getStatus().name()) + ",\"observedVoltage\":"
                + observedVoltage + ",\"repairTimeMinutes\":"
                + line.getRepairTimeMinutes() + "}}";
    }

    private String applyNodeVoltageEdit(String requestBody) {
        String nodeId = readStringField(requestBody, "nodeId");
        double voltage = readNumberField(requestBody, "newVoltage");
        if (!Double.isFinite(voltage) || voltage < 0 || voltage > 1000) {
            throw new IllegalArgumentException("Voltage must be between 0 and 1000 volts.");
        }
        if (network.getNode(nodeId) == null) {
            throw new IllegalArgumentException("Unknown node: " + nodeId);
        }
        nodeObservedVoltages.put(nodeId, voltage);
        sensorMonitor.updateIncidentLineVoltage(nodeId, voltage);
        int detectedFaultCount = detectAndSubmitFaults();
        return "{\"status\":\"ok\",\"node\":{\"id\":"
                + jsonString(nodeId) + ",\"observedVoltage\":" + voltage
                + "},\"detectedFaultCount\":" + detectedFaultCount + "}";
    }

    private void restoreNextFault() {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            throw new IllegalStateException("There are no pending faults to restore.");
        }
        if (restorationPolicy.hasPendingBackupApprovalDecision()) {
            throw new IllegalStateException(
                    "Review each proposed backup link before continuing repair.");
        }
        restorationPolicy.restoreNextFault(network, outageDetector);
        for (FaultReport report : restorationPolicy.getFaultReports()) {
            if (report.isLineRepairCompleted()) {
                sensorMonitor.clearAnomaly(report.getFailedLine().getId());
            }
        }
        restorationPolicy.refreshRestorationStatuses(network, outageDetector);
    }

    private void resetNetwork() {
        network.reset();
        restorationPolicy.reset();
        nodeObservedVoltages.clear();
        sensorMonitor = createSensorMonitor();
    }

    private boolean isNodeVoltageAlert(double voltage) {
        return SensorReading.isVoltageAbnormal(
                voltage, NORMAL_VOLTAGE, VOLTAGE_DROP_THRESHOLD_PERCENT);
    }

    private String backupApprovalsPayload() {
        StringBuilder json = new StringBuilder("{\"proposals\":[");
        List<BackupLinkProposal> proposals = restorationPolicy.getBackupLinkProposals();
        for (int i = 0; i < proposals.size(); i++) {
            BackupLinkProposal proposal = proposals.get(i);
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"lineId\":").append(jsonString(proposal.getBackupLineId()))
                    .append(",\"failedLineId\":")
                    .append(jsonString(proposal.getFailedLineId()))
                    .append(",\"currentStatus\":")
                    .append(jsonString(proposal.getBackupLine().getStatus().name()))
                    .append(",\"approvalStatus\":")
                    .append(jsonString(proposal.getApprovalStatus().name()))
                    .append(",\"path\":[");
            for (int p = 0; p < proposal.getRestorationPath().size(); p++) {
                if (p > 0) {
                    json.append(',');
                }
                json.append(jsonString(proposal.getRestorationPath().get(p)));
            }
            json.append("],\"affectedConsumers\":[");
            for (int c = 0; c < proposal.getAffectedConsumers().size(); c++) {
                if (c > 0) {
                    json.append(',');
                }
                NetworkNode consumer = proposal.getAffectedConsumers().get(c);
                json.append("{\"id\":").append(jsonString(consumer.getId()))
                        .append(",\"name\":").append(jsonString(consumer.getName()))
                        .append('}');
            }
            json.append("]}");
        }
        return json.append("]}").toString();
    }

    private static String readLineId(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Matcher matcher = Pattern.compile("\\\"lineId\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]+)\\\"")
                .matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("lineId is required.");
        }
        return matcher.group(1);
    }

    private String teamsPayload() {
        List<RepairTeam> teams = restorationPolicy.getRepairTeams();
        PriorityQueue<FaultReport> pending = restorationPolicy.getPendingFaultQueue();
        StringBuilder json = new StringBuilder("{\"teams\":[");
        for (int index = 0; index < teams.size(); index++) {
            RepairTeam team = teams.get(index);
            if (index > 0) {
                json.append(',');
            }
            FaultReport assignedReport = null;
            for (FaultReport report : pending) {
                if (report.getAssignedTeam() == team) {
                    assignedReport = report;
                    break;
                }
            }

            json.append("{\"id\":").append(jsonString(team.getId()))
                    .append(",\"name\":").append(jsonString(team.getName()))
                    .append(",\"depot\":").append(jsonString(team.getHomeLocation()))
                    .append(",\"status\":").append(jsonString(team.getStatus().name()))
                    .append(",\"assignedFault\":");
            if (assignedReport == null) {
                json.append("null,\"repairTimeMinutes\":null,\"travelTimeMinutes\":null,")
                        .append("\"estimatedRestorationTimeMinutes\":null,")
                        .append("\"estimatedRepairMinutes\":null}");
            } else {
                json.append(jsonString(assignedReport.getFailedLine().getId()))
                        .append(",\"repairTimeMinutes\":")
                        .append(assignedReport.getRepairTimeMinutes())
                        .append(",\"travelTimeMinutes\":")
                        .append(assignedReport.getTravelTimeMinutes())
                        .append(",\"estimatedRestorationTimeMinutes\":")
                        .append(assignedReport.getEstimatedRestorationTimeMinutes())
                        .append(",\"estimatedRepairMinutes\":")
                        .append(assignedReport.getEstimatedRepairTimeMinutes())
                        .append('}');
            }
        }
        return json.append("]}").toString();
    }

    private String queuePayload() {
        restorationPolicy.refreshRestorationStatuses(network, outageDetector);
        PriorityQueue<FaultReport> queue = restorationPolicy.getPendingFaultQueue();
        List<FaultReport> reports = new ArrayList<>();
        while (!queue.isEmpty()) {
            reports.add(queue.poll());
        }

        StringBuilder json = new StringBuilder("{\"outages\":[");
        for (int index = 0; index < reports.size(); index++) {
            FaultReport report = reports.get(index);
            if (index > 0) {
                json.append(',');
            }
            NetworkLine line = report.getFailedLine();
            RepairTeam team = report.getAssignedTeam();
            json.append("{\"reportId\":").append(report.getReportOrder())
                    .append(",\"faultId\":").append(jsonString(line.getId() + "-Fault"))
                    .append(",\"lineId\":").append(jsonString(line.getId()))
                    .append(",\"lineStatus\":").append(jsonString(line.getStatus().name()))
                    .append(",\"lineRepairStatus\":")
                    .append(jsonString(report.isLineRepairCompleted()
                            ? "LINE_REPAIRED" : "REPAIR_PENDING"));
            appendBooleanField(
                    json, "affectedConsumersRestored", report.areAffectedConsumersRestored());
            appendBooleanField(json, "networkFullyRestored", report.isNetworkFullyRestored());
            json.append(",\"priorityScore\":").append(report.getPriorityScore())
                    .append(",\"affectedNodes\":[");
            List<NetworkNode> affectedNodes = report.getAffectedNodes();
            for (int nodeIndex = 0; nodeIndex < affectedNodes.size(); nodeIndex++) {
                NetworkNode node = affectedNodes.get(nodeIndex);
                if (nodeIndex > 0) {
                    json.append(',');
                }
                json.append("{\"id\":").append(jsonString(node.getId()))
                        .append(",\"name\":").append(jsonString(node.getName()))
                        .append('}');
            }
            json.append("],\"affectedConsumers\":");
            appendAffectedNodes(json, report, false);
            json.append(",\"criticalFacilities\":");
            appendAffectedNodes(json, report, true);
            json.append(",\"assignedTeam\":");
            if (team == null) {
                json.append("null");
            } else {
                json.append("{\"id\":").append(jsonString(team.getId()))
                        .append(",\"name\":").append(jsonString(team.getName()))
                        .append('}');
            }
            json.append(",\"travelTimeMinutes\":")
                    .append(report.getTravelTimeMinutes())
                    .append(",\"repairTimeMinutes\":")
                    .append(report.getRepairTimeMinutes())
                    .append(",\"estimatedRestorationTimeMinutes\":")
                    .append(report.getEstimatedRestorationTimeMinutes())
                    .append(",\"estimatedRepairMinutes\":")
                    .append(report.getEstimatedRestorationTimeMinutes())
                    .append(",\"status\":").append(jsonString(report.getTeamStatus()))
                    .append('}');
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

    private static void appendRestorationReport(StringBuilder json, FaultReport report) {
        NetworkLine line = report.getFailedLine();
        RepairTeam team = report.getAssignedTeam();
        json.append("{\"reportId\":").append(report.getReportOrder())
                .append(",\"lineId\":").append(jsonString(line.getId()))
                .append(",\"lineStatus\":").append(jsonString(line.getStatus().name()));
        appendBooleanField(json, "lineRepairCompleted", report.isLineRepairCompleted());
        appendBooleanField(
                json, "affectedConsumersRestored", report.areAffectedConsumersRestored());
        appendBooleanField(json, "networkFullyRestored", report.isNetworkFullyRestored());
        json.append(",\"affectedConsumers\":");
        appendAffectedNodes(json, report, false);
        json.append(",\"criticalFacilities\":");
        appendAffectedNodes(json, report, true);
        json.append(",\"assignedTeam\":");
        if (team == null) {
            json.append("null");
        } else {
            json.append("{\"id\":").append(jsonString(team.getId()))
                    .append(",\"name\":").append(jsonString(team.getName())).append('}');
        }
        json.append(",\"repairTimeMinutes\":").append(report.getRepairTimeMinutes())
                .append(",\"travelTimeMinutes\":").append(report.getTravelTimeMinutes())
                .append(",\"estimatedRestorationTimeMinutes\":")
                .append(report.getEstimatedRestorationTimeMinutes()).append('}');
    }

    private String historyPayload() throws IOException {
        List<String[]> records = historyManager.loadHistory();
        StringBuilder json = new StringBuilder("{\"history\":[");
        for (int index = 0; index < records.size(); index++) {
            String[] row = records.get(index);
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"timestamp\":").append(jsonString(row[0]))
                    .append(",\"eventType\":").append(jsonString(row[1]))
                    .append(",\"lineId\":").append(jsonString(row[2]))
                    .append(",\"affectedZones\":").append(jsonString(row[3]))
                    .append(",\"assignedTeam\":").append(jsonString(row[4]))
                    .append(",\"status\":").append(jsonString(row[5]))
                    .append('}');
        }
        return json.append("]}").toString();
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

    private static int[] nodePosition(String nodeId) {
        int x;
        int y;
        switch (nodeId) {
            case "S1":
            case "S2":
                x = 100;
                break;
            case "F1":
            case "F2":
            case "F3":
            case "F4":
                x = 365;
                break;
            case "C1":
            case "C4":
            case "C5":
            case "C6":
            case "C8":
            case "C10":
            case "H1":
                x = 700;
                break;
            case "C2":
            case "C3":
            case "C7":
            case "C9":
            case "C11":
            case "E1":
            case "E2":
                x = 1000;
                break;
            default:
                x = 60;
                break;
        }
        switch (nodeId) {
            case "S1":
                y = 300;
                break;
            case "S2":
                y = 730;
                break;
            case "F1":
                y = 160;
                break;
            case "F2":
                y = 400;
                break;
            case "F3":
                y = 660;
                break;
            case "F4":
                y = 900;
                break;
            case "C1":
            case "C2":
                y = 95;
                break;
            case "C6":
            case "C7":
                y = 235;
                break;
            case "H1":
            case "C3":
                y = 385;
                break;
            case "C8":
            case "C9":
                y = 525;
                break;
            case "C4":
            case "E1":
                y = 665;
                break;
            case "C10":
            case "E2":
                y = 805;
                break;
            case "C5":
            case "C11":
                y = 945;
                break;
            default:
                y = 60;
                break;
        }
        return new int[] {x, y};
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\b':
                    escaped.append("\\b");
                    break;
                case '\f':
                    escaped.append("\\f");
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
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                    break;
            }
        }
        return escaped.append('"').toString();
    }

    private static void appendBooleanField(StringBuilder json, String fieldName, boolean value) {
        json.append(",\"").append(fieldName).append("\":").append(value);
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static boolean isGetEndpoint(String path) {
        return "/api/network".equals(path)
                || "/api/backup-approvals".equals(path)
                || "/api/teams".equals(path)
                || "/api/queue".equals(path)
                || "/api/outages".equals(path)
                || "/api/history".equals(path)
                || "/api/health".equals(path);
    }

    private static boolean isPostEndpoint(String path) {
        return "/api/actions/backup-approve".equals(path)
                || "/api/actions/backup-reject".equals(path)
                || "/api/actions/backup-activate".equals(path)
                || "/api/actions/simulate-fault".equals(path)
                || "/api/actions/run-detection".equals(path)
                || "/api/actions/auto-restore".equals(path)
                || "/api/actions/reset".equals(path)
                || "/api/action".equals(path)
                || "/api/node-voltage".equals(path);
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        try {
            exchange.sendResponseHeaders(status, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        } finally {
            exchange.close();
        }
    }
}

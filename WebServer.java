import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebServer {
    private static final int PORT = 8081;

    private final DistributionNetwork network;
    private final OutageDetector outageDetector = new OutageDetector();
    private final RestorationPolicy restorationPolicy;
    private final HistoryManager historyManager;

    private WebServer() {
        network = DistributionNetwork.createDemonstrationNetwork();
        historyManager = new HistoryManager();
        restorationPolicy = RestorationPolicy.withHistoryManager(historyManager);
    }

    public static void main(String[] args) throws IOException {
        WebServer application = new WebServer();
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", application::handleRequest);
        server.start();
        System.out.println("SmartGrid HTTP API running on http://localhost:" + PORT);
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
        try {
            if ("POST".equalsIgnoreCase(method)) {
                String lineId = readLineId(exchange);
                switch (path) {
                    case "/api/actions/backup-approve":
                        restorationPolicy.approveBackupLink(lineId);
                        break;
                    case "/api/actions/backup-reject":
                        restorationPolicy.rejectBackupLink(lineId);
                        break;
                    case "/api/actions/backup-activate":
                        restorationPolicy.activateApprovedBackupLink(
                                lineId, network, new OutageDetector());
                        break;
                    default:
                        sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                        return;
                }
                sendJson(exchange, 200, "{\"status\":\"ok\"}");
                return;
            }
            if (!"GET".equalsIgnoreCase(method)) {
                sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
                return;
            }
            switch (path) {
                case "/api/network":
                    sendJson(exchange, 200, networkPayload());
                    break;
                case "/api/backup-approvals":
                    sendJson(exchange, 200, backupApprovalsPayload());
                    break;
                case "/api/teams":
                    sendJson(exchange, 200, teamsPayload());
                    break;
                case "/api/queue":
                case "/api/outages":
                    sendJson(exchange, 200, queuePayload());
                    break;
                case "/api/history":
                    sendJson(exchange, 200, historyPayload());
                    break;
                default:
                    sendJson(exchange, 404, "{\"error\":\"Not found\"}");
                    break;
            }
        } catch (IOException exception) {
            sendJson(exchange, 500,
                    "{\"error\":" + jsonString(exception.getMessage()) + "}");
        } catch (RuntimeException exception) {
            sendJson(exchange, 500,
                    "{\"error\":" + jsonString(exception.getMessage()) + "}");
        }
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
                    .append(",\"observedVoltage\":230")
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
            json.append("{\"faultId\":").append(jsonString(line.getId() + "-Fault"))
                    .append(",\"lineId\":").append(jsonString(line.getId()))
                    .append(",\"lineStatus\":").append(jsonString(line.getStatus().name()))
                    .append(",\"lineRepairStatus\":")
                    .append(jsonString(report.isLineRepairCompleted()
                            ? "LINE_REPAIRED" : "REPAIR_PENDING"))
                    .append(",\"affectedConsumersRestored\":")
                    .append(report.areAffectedConsumersRestored())
                    .append(",\"networkFullyRestored\":")
                    .append(report.isNetworkFullyRestored())
                    .append(",\"priorityScore\":").append(report.getPriorityScore())
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
        json.append("{\"lineId\":").append(jsonString(line.getId()))
                .append(",\"lineStatus\":").append(jsonString(line.getStatus().name()))
                .append(",\"lineRepairCompleted\":").append(report.isLineRepairCompleted())
                .append(",\"affectedConsumersRestored\":")
                .append(report.areAffectedConsumersRestored())
                .append(",\"networkFullyRestored\":")
                .append(report.isNetworkFullyRestored())
                .append(",\"affectedConsumers\":");
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

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static void sendJson(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(
                "Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(status, response.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(response);
        } finally {
            exchange.close();
        }
    }
}

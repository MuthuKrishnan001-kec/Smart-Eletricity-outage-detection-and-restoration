import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

public final class WebServer {
    private static final int PORT = 8081;

    private final DistributionNetwork network;
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
        if (!"GET".equalsIgnoreCase(method)) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        String path = exchange.getRequestURI().getPath();
        try {
            switch (path) {
                case "/api/network":
                    sendJson(exchange, 200, networkPayload());
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
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":").append(jsonString(line.getId()))
                    .append(",\"from\":").append(jsonString(line.getFromNodeId()))
                    .append(",\"to\":").append(jsonString(line.getToNodeId()))
                    .append(",\"status\":").append(jsonString(line.getStatus().name()))
                    .append(",\"backup\":").append(line.isBackupLink())
                    .append(",\"observedVoltage\":230")
                    .append(",\"repairTimeMinutes\":").append(line.getRepairTimeMinutes())
                    .append('}');
        }
        return json.append("],\"summary\":{\"totalNodes\":")
                .append(nodes.size())
                .append(",\"totalLines\":").append(lines.size())
                .append("}}").toString();
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
                json.append("null,\"estimatedRepairMinutes\":null}");
            } else {
                json.append(jsonString(assignedReport.getFailedLine().getId()))
                        .append(",\"estimatedRepairMinutes\":")
                        .append(assignedReport.getEstimatedRepairTimeMinutes())
                        .append('}');
            }
        }
        return json.append("]}").toString();
    }

    private String queuePayload() {
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
            json.append("],\"assignedTeam\":");
            if (team == null) {
                json.append("null");
            } else {
                json.append("{\"id\":").append(jsonString(team.getId()))
                        .append(",\"name\":").append(jsonString(team.getName()))
                        .append('}');
            }
            json.append(",\"estimatedRepairMinutes\":")
                    .append(report.getEstimatedRepairTimeMinutes())
                    .append(",\"status\":").append(jsonString(report.getTeamStatus()))
                    .append('}');
        }
        return json.append("]}").toString();
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
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, OPTIONS");
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

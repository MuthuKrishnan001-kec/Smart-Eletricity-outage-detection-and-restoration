import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DistributionNetwork {
    private final Map<String, NetworkNode> nodesById = new HashMap<>();
    private final Map<String, NetworkLine> linesById = new HashMap<>();
    private final Map<String, List<NetworkLine>> adjacency = new HashMap<>();
    private final List<NetworkNode> nodes = new ArrayList<>();
    private final List<NetworkLine> lines = new ArrayList<>();

    public void addNode(NetworkNode node) {
        Objects.requireNonNull(node, "node");
        String nodeId = Objects.requireNonNull(node.getId(), "node ID");
        if (nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("Node ID must not be empty.");
        }
        if (nodesById.containsKey(nodeId)) {
            throw new IllegalArgumentException("Duplicate node ID: " + nodeId);
        }
        nodesById.put(nodeId, node);
        nodes.add(node);
        adjacency.put(nodeId, new ArrayList<>());
    }

    public void addLine(NetworkLine line) {
        Objects.requireNonNull(line, "line");
        if (linesById.containsKey(line.getId())) {
            throw new IllegalArgumentException("Duplicate line ID: " + line.getId());
        }
        if (!nodesById.containsKey(line.getFromNodeId())
                || !nodesById.containsKey(line.getToNodeId())) {
            throw new IllegalArgumentException("Line references an unknown node: " + line.getId());
        }

        linesById.put(line.getId(), line);
        lines.add(line);
        adjacency.get(line.getFromNodeId()).add(line);
        adjacency.get(line.getToNodeId()).add(line);
    }

    public NetworkNode getNode(String id) {
        return nodesById.get(id);
    }

    public NetworkLine getLine(String id) {
        return linesById.get(id);
    }

    public List<NetworkNode> getNodes() {
        return new ArrayList<>(nodes);
    }

    public List<NetworkLine> getLines() {
        return new ArrayList<>(lines);
    }

    public List<NetworkLine> getIncidentLines(String nodeId) {
        List<NetworkLine> incidentLines = adjacency.get(nodeId);
        return incidentLines == null ? new ArrayList<>() : new ArrayList<>(incidentLines);
    }

    public String getOtherNodeId(NetworkLine line, String nodeId) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(nodeId, "nodeId");
        if (line.getFromNodeId().equals(nodeId)) {
            return line.getToNodeId();
        }
        if (line.getToNodeId().equals(nodeId)) {
            return line.getFromNodeId();
        }
        throw new IllegalArgumentException("Node is not an endpoint of line " + line.getId());
    }

    public List<Substation> getSubstations() {
        List<Substation> substations = new ArrayList<>();
        for (NetworkNode node : nodes) {
            if (node instanceof Substation) {
                substations.add((Substation) node);
            }
        }
        return substations;
    }

    public void reset() {
        for (NetworkLine line : lines) {
            line.restoreLine();
        }
        for (NetworkNode node : nodes) {
            node.setPowered(true);
        }
    }

    public static DistributionNetwork createDemonstrationNetwork() {
        DistributionNetwork network = new DistributionNetwork();

        network.addNode(new Substation("S1", "North Substation", true));
        network.addNode(new Substation("S2", "South Substation", true));
        network.addNode(new Feeder("F1", "Feeder North-1", true));
        network.addNode(new Feeder("F2", "Feeder North-2", true));
        network.addNode(new Feeder("F3", "Feeder South-1", true));
        network.addNode(new Feeder("F4", "Feeder South-2", true));
        network.addNode(new GeneralArea("C1", "Greenfield", true, 1));
        network.addNode(new GeneralArea("C2", "Lakeview", true, 1));
        network.addNode(new GeneralArea("C3", "Hilltop", true, 1));
        network.addNode(new GeneralArea("C4", "Riverside", true, 1));
        network.addNode(new GeneralArea("C5", "Sunrise Colony", true, 1));
        network.addNode(new GeneralArea("C6", "Westfield", true, 1));
        network.addNode(new GeneralArea("C7", "Cedar Grove", true, 1));
        network.addNode(new GeneralArea("C8", "Central Market", true, 1));
        network.addNode(new GeneralArea("C9", "Oakwood", true, 1));
        network.addNode(new GeneralArea("C10", "Maple Estate", true, 1));
        network.addNode(new GeneralArea("C11", "East Park", true, 1));
        network.addNode(new Hospital("H1", "City General Hospital", true, 1));
        network.addNode(new EmergencyCentre(
                "E1", "District Emergency Response Centre", true, 1));
        network.addNode(new EmergencyCentre("E2", "Fire and Rescue Station", true, 1));

        network.addLine(new NetworkLine("L1", "S1", "F1", false, 25));
        network.addLine(new NetworkLine("L2", "S1", "F2", false, 40));
        network.addLine(new NetworkLine("L3", "S2", "F3", false, 25));
        network.addLine(new NetworkLine("L4", "S2", "F4", false, 30));
        network.addLine(new NetworkLine("L5", "F1", "C1", false, 20));
        network.addLine(new NetworkLine("L6", "F1", "C2", false, 20));
        network.addLine(new NetworkLine("L7", "F2", "H1", false, 35));
        network.addLine(new NetworkLine("L8", "F2", "C3", false, 20));
        network.addLine(new NetworkLine("L9", "F3", "C4", false, 20));
        network.addLine(new NetworkLine("L10", "F3", "E1", false, 35));
        network.addLine(new NetworkLine("L11", "F4", "C5", false, 20));
        network.addLine(new NetworkLine("L12", "F2", "S2", true, 15));
        network.addLine(new NetworkLine("L13", "F3", "S1", true, 15));
        network.addLine(new NetworkLine("L14", "F1", "F4", true, 15));
        network.addLine(new NetworkLine("L15", "F1", "C6", false, 20));
        network.addLine(new NetworkLine("L16", "F1", "C7", false, 20));
        network.addLine(new NetworkLine("L17", "F2", "C8", false, 20));
        network.addLine(new NetworkLine("L18", "F2", "C9", false, 20));
        network.addLine(new NetworkLine("L19", "F3", "C10", false, 20));
        network.addLine(new NetworkLine("L20", "F3", "E2", false, 35));
        network.addLine(new NetworkLine("L21", "F4", "C11", false, 20));

        return network;
    }
}

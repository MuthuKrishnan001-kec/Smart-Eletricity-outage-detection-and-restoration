import java.util.*;
import java.util.stream.Collectors;

public class ElectricityNetwork {
    private final Map<String, NetworkNode> nodes = new HashMap<>();
    private final Map<String, List<NetworkEdge>> adjacency = new HashMap<>();
    private final List<NetworkEdge> allEdges = new ArrayList<>();

    public void addNode(NetworkNode node) {
        Objects.requireNonNull(node);
        if (nodes.containsKey(node.getId())) {
            throw new IllegalArgumentException("Duplicate node ID: " + node.getId());
        }
        nodes.put(node.getId(), node);
        adjacency.put(node.getId(), new ArrayList<>());
    }

    public void addEdge(NetworkEdge edge) {
        Objects.requireNonNull(edge);
        if (!nodes.containsKey(edge.getFromNodeId()) || !nodes.containsKey(edge.getToNodeId())) {
            throw new IllegalArgumentException("Edge references unknown node(s): " + edge);
        }
        adjacency.get(edge.getFromNodeId()).add(edge);
        allEdges.add(edge);
    }

    public NetworkNode getNode(String id) {
        return nodes.get(id);
    }

    public List<NetworkNode> getAllNodes() {
        return new ArrayList<>(nodes.values());
    }

    public List<NetworkEdge> getAllEdges() {
        return new ArrayList<>(allEdges);
    }

    public List<NetworkEdge> getOutgoingEdges(String nodeId) {
        return adjacency.getOrDefault(nodeId, Collections.emptyList());
    }

    public void deactivateEdge(NetworkEdge edge) {
        edge.setActive(false);
    }

    public String summary() {
        Map<NodeType, Long> typeCounts = nodes.values().stream()
                .collect(Collectors.groupingBy(NetworkNode::getType, Collectors.counting()));
        long substations = typeCounts.getOrDefault(NodeType.SUBSTATION, 0L);
        long feeders = typeCounts.getOrDefault(NodeType.FEEDER, 0L);
        long transformers = typeCounts.getOrDefault(NodeType.TRANSFORMER, 0L);
        long customers = typeCounts.getOrDefault(NodeType.CUSTOMER, 0L);
        long hospitals = nodes.values().stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.HOSPITAL)
                .count();
        long water = nodes.values().stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.WATER_PUMPING_STATION)
                .count();
        long emergency = nodes.values().stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.EMERGENCY_CENTRE)
                .count();
        long alternate = allEdges.stream()
                .filter(e -> e.getFromNodeId().startsWith("SS") && e.getToNodeId().startsWith("SS"))
                .count();
        return String.format(
                "Substations       : %d%nFeeders           : %d%nTransformers      : %d%nCustomers         : %d%nHospitals         : %d%nWater Stations    : %d%nEmergency Centres : %d%nAlternate Links   : %d%nTotal Nodes       : %d%nTotal Edges       : %d",
                substations, feeders, transformers, customers,
                hospitals, water, emergency,
                alternate,
                nodes.size(), allEdges.size()
        );
    }

    public void resetAllEdges() {
        for (NetworkEdge edge : allEdges) {
            edge.setActive(true);
        }
    }

    public java.util.Optional<NetworkEdge> findEdge(String fromId, String toId) {
        return allEdges.stream()
                .filter(e -> e.getFromNodeId().equals(fromId) && e.getToNodeId().equals(toId))
                .findFirst();
    }

    public java.util.Optional<NetworkEdge> getEdgeById(String id) {
        return allEdges.stream()
                .filter(e -> e.getId().equals(id))
                .findFirst();
    }
}

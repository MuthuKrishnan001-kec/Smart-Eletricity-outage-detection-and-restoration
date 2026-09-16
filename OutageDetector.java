import java.util.*;

public class OutageDetector {
    public static Set<NetworkNode> detectOutage(ElectricityNetwork network) {
        Set<NetworkNode> reachable = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        for (NetworkNode node : network.getAllNodes()) {
            if (node.getType() == NodeType.SUBSTATION && node.isPowered()) {
                reachable.add(node);
                queue.add(node.getId());
            }
        }

        while (!queue.isEmpty()) {
            String curId = queue.poll();
            for (NetworkEdge edge : network.getOutgoingEdges(curId)) {
                if (!edge.isActive()) continue;
                NetworkNode toNode = network.getNode(edge.getToNodeId());
                if (toNode == null) continue;
                if (reachable.add(toNode)) {
                    queue.add(toNode.getId());
                }
            }
        }
        return reachable;
    }

    public static int countAffectedCustomers(Set<NetworkNode> affected) {
        return (int) affected.stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER)
                .count();
    }
    public static int countAffectedTransformers(Set<NetworkNode> affected) {
        return (int) affected.stream()
                .filter(n -> n.getType() == NodeType.TRANSFORMER)
                .count();
    }
    public static int countAffectedHospitals(Set<NetworkNode> affected) {
        return (int) affected.stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.HOSPITAL)
                .count();
    }
    public static int countAffectedWaterStations(Set<NetworkNode> affected) {
        return (int) affected.stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.WATER_PUMPING_STATION)
                .count();
    }
    public static int countAffectedEmergencyCenters(Set<NetworkNode> affected) {
        return (int) affected.stream()
                .filter(n -> n.getType() == NodeType.CUSTOMER && n.getCustomerType() == CustomerType.EMERGENCY_CENTRE)
                .count();
    }
}

import java.util.*;

public class Main {
    private static Set<NetworkNode> lastAffectedNodes = new HashSet<>();
    private static String lastFailedEdgeId = null;

    public static void main(String[] args) {
        NetworkConfig config = new NetworkConfig();
        NetworkBuilder builder = new NetworkBuilder(config);
        ElectricityNetwork network = builder.build();

        System.out.println("=========================================");
        System.out.println(" SMART ELECTRICITY OUTAGE DETECTION");
        System.out.println("=========================================");
        System.out.println();
        System.out.println(network.summary());
        System.out.println();

        runMenu(network);
    }

    private static void runMenu(ElectricityNetwork network) {
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.println("=========================================");
            System.out.println(" SMART ELECTRICITY OUTAGE DETECTION");
            System.out.println("=========================================");
            System.out.println();
            System.out.println("1. Display Network");
            System.out.println("2. Simulate Line Fault");
            System.out.println("3. Detect Outage");
            System.out.println("4. Display Affected Nodes");
            System.out.println("5. Reset Network");
            System.out.println("6. Exit");
            System.out.print("Enter choice: ");
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    displayNetwork(network);
                    break;
                case "2":
                    simulateFault(network, scanner);
                    break;
                case "3":
                    detectOutage(network, null);
                    break;
                case "4":
                    displayAffectedNodes();
                    break;
                case "5":
                    resetNetwork(network);
                    break;
                case "6":
                    System.out.println("Program terminated.");
                    return;
                default:
                    System.out.println("Invalid choice. Please try again.");
            }
        }
    }

    private static void displayNetwork(ElectricityNetwork network) {
        System.out.println("========== NETWORK INFORMATION ==========");
        System.out.println(network.summary());
        System.out.println();
    }

    private static void simulateFault(ElectricityNetwork network, Scanner scanner) {
        System.out.println("========== FAULT SIMULATION ==========");
        List<NetworkEdge> feederEdges = new ArrayList<>();
        for (NetworkEdge edge : network.getAllEdges()) {
            NetworkNode from = network.getNode(edge.getFromNodeId());
            NetworkNode to = network.getNode(edge.getToNodeId());
            if (from != null && to != null && from.getType() == NodeType.SUBSTATION && to.getType() == NodeType.FEEDER) {
                feederEdges.add(edge);
            }
        }
        if (feederEdges.isEmpty()) {
            System.out.println("No feeder lines found.");
            return;
        }
        System.out.println("Available Lines:");
        int count = 0;
        for (NetworkEdge e : feederEdges) {
            System.out.print(e.getId() + " ");
            if (++count % 10 == 0) System.out.println();
        }
        System.out.println();
        System.out.print("Enter Line ID: ");
        String lineId = scanner.nextLine().trim();
        Optional<NetworkEdge> optEdge = network.getEdgeById(lineId);
        if (!optEdge.isPresent()) {
            System.out.println("Line " + lineId + " not found.");
            System.out.println("Please enter a valid line ID.");
            return;
        }
        NetworkEdge edge = optEdge.get();
        if (!edge.isActive()) {
            System.out.println("Line " + lineId + " is already FAILED.");
            return;
        }
        System.out.print("Enter estimated repair time (minutes): ");
        String repairStr = scanner.nextLine().trim();
        int repairTime = 0;
        try { repairTime = Integer.parseInt(repairStr); } catch (NumberFormatException ignored) {}
        network.deactivateEdge(edge);
        System.out.println();
        System.out.println("========== FAULT SIMULATION ==========");
        System.out.println("Line ID          : " + lineId);
        System.out.println("Estimated Repair : " + repairTime + " minutes");
        System.out.println("Previous Status  : WORKING");
        System.out.println("Current Status   : FAILED");
        System.out.println();
        System.out.println("Fault simulated successfully.");
        System.out.println("Running BFS...");
        detectOutage(network, lineId);
    }

    private static void detectOutage(ElectricityNetwork network, String triggeredEdgeId) {
        Set<NetworkNode> powered = OutageDetector.detectOutage(network);
        Set<NetworkNode> all = new HashSet<>(network.getAllNodes());
        Set<NetworkNode> affected = new HashSet<>(all);
        affected.removeAll(powered);
        lastAffectedNodes = affected;
        lastFailedEdgeId = triggeredEdgeId != null ? triggeredEdgeId : lastFailedEdgeId;
        System.out.println();
        System.out.println("========== OUTAGE DETECTION ==========");
        System.out.println("Detection Method : BFS");
        if (lastFailedEdgeId != null) {
            System.out.println("Failed Line      : " + lastFailedEdgeId);
        }
        System.out.println();
        System.out.println("Powered Nodes    : " + powered.size());
        System.out.println("Affected Nodes   : " + affected.size());
        System.out.println();
        System.out.println("Affected Customers        : " + OutageDetector.countAffectedCustomers(affected));
        System.out.println("Affected Transformers     : " + OutageDetector.countAffectedTransformers(affected));
        System.out.println("Affected Hospitals        : " + OutageDetector.countAffectedHospitals(affected));
        System.out.println("Affected Water Stations   : " + OutageDetector.countAffectedWaterStations(affected));
        System.out.println("Affected Emergency Centres: " + OutageDetector.countAffectedEmergencyCenters(affected));
        System.out.println();
    }

    private static void displayAffectedNodes() {
        if (lastAffectedNodes == null || lastAffectedNodes.isEmpty()) {
            System.out.println("No affected nodes (network is fully powered).");
            return;
        }
        System.out.println("========== AFFECTED AREA ==========\n");
        System.out.println("Affected Nodes:\n");
        List<String> ids = new ArrayList<>();
        for (NetworkNode n : lastAffectedNodes) {
            ids.add(n.getId());
        }
        Collections.sort(ids);
        int limit = 20;
        for (int i = 0; i < Math.min(limit, ids.size()); i++) {
            System.out.println(ids.get(i));
        }
        if (ids.size() > limit) {
            System.out.println("... and " + (ids.size() - limit) + " more affected nodes");
        }
        System.out.println("\nTotal Affected Nodes: " + ids.size());
        System.out.println();
    }

    private static void resetNetwork(ElectricityNetwork network) {
        network.resetAllEdges();
        lastAffectedNodes.clear();
        lastFailedEdgeId = null;
        System.out.println("========== NETWORK RESET ==========");
        System.out.println("All lines restored to WORKING.");
        System.out.println("Outage information cleared.");
        System.out.println("Network is back to normal.");
        System.out.println();
    }
}
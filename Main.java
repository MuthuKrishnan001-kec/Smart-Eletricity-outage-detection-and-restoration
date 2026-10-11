import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

public class Main {
    private final DistributionNetwork network =
            DistributionNetwork.createDemonstrationNetwork();
    private final OutageDetector outageDetector = new OutageDetector();
    private final SensorMonitor sensorMonitor = createSensorMonitor();
    private final RestorationPolicy restorationPolicy = new RestorationPolicy();
    private List<NetworkNode> lastAffectedNodes = new ArrayList<>();
    private NetworkLine lastFailedLine;

    public static void main(String[] args) {
        new Main().run();
    }

    private void run() {
        System.out.println("===============================================");
        System.out.println("     SMART ELECTRICITY OUTAGE DETECTION");
        System.out.println("===============================================");
        System.out.println("13 nodes | 14 lines | Simulated sensor readings");

        Scanner scanner = new Scanner(System.in);
        while (true) {
            showMenu();
            if (!scanner.hasNextLine()) {
                return;
            }
            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1":
                    displayNetwork();
                    break;
                case "2":
                    simulateManualFault(scanner);
                    break;
                case "3":
                    runSensorDetection();
                    break;
                case "4":
                    displayAffectedNodes();
                    break;
                case "5":
                    restoreNetwork();
                    break;
                case "6":
                    resetNetwork();
                    break;
                case "7":
                    System.out.println("Program terminated.");
                    return;
                default:
                    System.out.println("Invalid menu choice. Enter a number from 1 to 7.");
            }
        }
    }

    private void showMenu() {
        System.out.println();
        System.out.println("1. Display Network");
        System.out.println("2. Manual Line Fault");
        System.out.println("3. Automatic Sensor Fault Detection");
        System.out.println("4. Display Affected Nodes");
        System.out.println("5. Restore Network");
        System.out.println("6. Reset Network");
        System.out.println("7. Exit");
        System.out.print("Enter choice: ");
    }

    private void displayNetwork() {
        System.out.println("\n================ NETWORK ================");
        for (NetworkNode node : network.getNodes()) {
            System.out.printf("%-3s %-42s %s%n",
                    node.getId(), node.getName(), node.getNodeLabel());
        }
        System.out.println("\nLines:");
        for (NetworkLine line : network.getLines()) {
            System.out.printf("%-4s %s <-> %s  %s%s%n",
                    line.getId(), line.getFromNodeId(), line.getToNodeId(),
                    line.getStatus(), line.isBackupLink() ? " (backup)" : "");
        }
    }

    private void simulateManualFault(Scanner scanner) {
        System.out.println("\n=============== MANUAL LINE FAULT ===============");
        for (NetworkLine line : network.getLines()) {
            System.out.printf("%-4s %s <-> %s  %s%n",
                    line.getId(), line.getFromNodeId(), line.getToNodeId(), line.getStatus());
        }
        System.out.print("Enter a WORKING line ID to fail: ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String lineId = scanner.nextLine().trim().toUpperCase();
        NetworkLine line = network.getLine(lineId);
        if (line == null) {
            System.out.println("Line " + lineId + " was not found. No changes made.");
            return;
        }
        if (!line.isWorking()) {
            System.out.println(lineId + " is not WORKING, so it cannot be failed manually.");
            return;
        }
        processFault(line, "Manual fault selected by user");
    }

    private void runSensorDetection() {
        System.out.println("\n================ SENSOR MONITORING ================");
        System.out.println("Simulated Sensor-Based Fault Detection");
        System.out.println("Line   Voltage   Current   Status");
        System.out.println("-----------------------------------------------");

        for (NetworkLine line : network.getLines()) {
            SensorReading reading = sensorMonitor.getReading(line.getId());
            if (reading == null) {
                continue;
            }
            System.out.printf("%-6s %6.0f V   %5.0f A   %s%n",
                    line.getId(), reading.getObservedVoltage(),
                    reading.getObservedCurrent(),
                    sensorMonitor.isAbnormal(reading) ? "ABNORMAL" : "NORMAL");
        }

        OutageDiagnostics diagnostics = outageDetector.detectFaults(network, sensorMonitor);
        if (diagnostics.getFaultedLines().isEmpty()) {
            System.out.println("No failed lines or voltage faults detected.");
            return;
        }

        System.out.println("\n============= AUTOMATIC FAULT DETECTION =============");
        for (NetworkLine line : diagnostics.getFaultedLines()) {
            if (restorationPolicy.isPendingFault(line)) {
                continue;
            }
            SensorReading reading = sensorMonitor.getReading(line.getId());
            System.out.println("Fault detected on " + line.getId()
                    + " (status: " + line.getStatus() + ")");
            if (reading != null) {
                System.out.printf("Normal Voltage  : %.0f V%n", reading.getNormalVoltage());
                System.out.printf("Observed Voltage: %.0f V%n", reading.getObservedVoltage());
                System.out.println("Voltage fault: "
                        + sensorMonitor.isVoltageFault(reading));
            }
            FaultReport report = new FaultReport(
                    line, orderAffectedNodes(line, diagnostics.getAffectedNodes()));
            restorationPolicy.submitFault(report);
            lastFailedLine = line;
            displayFaultReport(report);
            System.out.println("Fault added to restoration PriorityQueue.");
        }
        lastAffectedNodes = new ArrayList<>(diagnostics.getAffectedNodes());
    }

    private void processFault(NetworkLine line, String faultSource) {
        if (!line.isWorking()) {
            System.out.println("Line " + line.getId() + " is not working; fault was not submitted.");
            return;
        }

        line.failLine();
        lastFailedLine = line;
        System.out.println(faultSource + ": " + line.getId() + " marked FAILED.");

        lastAffectedNodes = outageDetector.findAffectedNodes(network);

        FaultReport report = new FaultReport(line, orderAffectedNodes(line, lastAffectedNodes));
        restorationPolicy.submitFault(report);
        displayFaultReport(report);
        System.out.println("Fault added to restoration PriorityQueue.");
        System.out.println("Select option 5 to start restoration.");
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
                .thenComparing((NetworkNode node) -> node instanceof ConsumerArea
                        ? -((ConsumerArea) node).getPriorityWeight() : 0)
                .thenComparing(NetworkNode::getId));
        return ordered;
    }

    private void displayFaultReport(FaultReport report) {
        System.out.println("\n================ OUTAGE DETECTION ================");
        System.out.println("Method             : Multi-source BFS");
        System.out.println("Failed Line        : " + report.getFailedLine().getId());
        System.out.println("Affected Nodes:");
        for (NetworkNode node : report.getAffectedNodes()) {
            System.out.println(node.getId() + " - " + node.getName());
        }

        boolean hasCriticalArea = false;
        for (NetworkNode node : report.getAffectedNodes()) {
            if (node instanceof CriticalArea) {
                if (!hasCriticalArea) {
                    System.out.println("Critical Facility:");
                    hasCriticalArea = true;
                }
                System.out.println(node.getName());
            }
        }
        System.out.println("Affected Consumers: " + countAffectedConsumers(report));
        System.out.println("Priority Score    : " + report.getPriorityScore());
    }

    private int countAffectedConsumers(FaultReport report) {
        int count = 0;
        for (NetworkNode node : report.getAffectedNodes()) {
            if (node instanceof ConsumerArea) {
                count++;
            }
        }
        return count;
    }

    private void displayAffectedNodes() {
        System.out.println("\n================ AFFECTED NODES ================");
        if (lastAffectedNodes.isEmpty()) {
            System.out.println("No affected nodes.");
            return;
        }
        for (NetworkNode node : orderAffectedNodes(lastFailedLine, lastAffectedNodes)) {
            System.out.println(node.getId() + " - " + node.getName());
        }
    }

    private void restoreNetwork() {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            System.out.println("There are no faults waiting for restoration.");
            return;
        }
        restorationPolicy.restoreNextFault(network, outageDetector);
        lastAffectedNodes = outageDetector.findAffectedNodes(network);
        if (lastAffectedNodes.isEmpty()) {
            lastFailedLine = null;
        }
    }

    private void resetNetwork() {
        network.reset();
        restorationPolicy.reset();
        lastAffectedNodes.clear();
        lastFailedLine = null;
        System.out.println("Network reset: normal lines WORKING, backup lines STANDBY.");
    }

    private SensorMonitor createSensorMonitor() {
        SensorMonitor monitor = new SensorMonitor(20, 30);
        for (NetworkLine line : network.getLines()) {
            double normalCurrent = 10;
            double observedVoltage = 230;
            double observedCurrent = normalCurrent;

            if ("L1".equals(line.getId())) {
                normalCurrent = 12;
                observedVoltage = 229;
                observedCurrent = 12;
            } else if ("L2".equals(line.getId())) {
                normalCurrent = 15;
                observedVoltage = 45;
                observedCurrent = 2;
            } else if ("L3".equals(line.getId())) {
                normalCurrent = 10;
                observedVoltage = 228;
            } else if ("L4".equals(line.getId())) {
                normalCurrent = 11;
                observedVoltage = 231;
                observedCurrent = 11;
            }

            monitor.addReading(new SensorReading(
                    line.getId(), 230, normalCurrent, observedVoltage, observedCurrent));
        }
        return monitor;
    }
}

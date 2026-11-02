import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Scanner;

public class Main {
    private final DistributionNetwork network =
            DistributionNetwork.createDemonstrationNetwork();
    private final OutageDetector outageDetector = new OutageDetector();
    private SensorMonitor sensorMonitor = createSensorMonitor();
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
        System.out.println(network.getNodes().size() + " nodes | "
                + network.getLines().size() + " lines | Simulated city grid");

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
                    runSensorDetection(scanner);
                    break;
                case "4":
                    displayAffectedNodes();
                    break;
                case "5":
                    restoreNetwork(scanner);
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
        System.out.println("3. Simulate City Event and Run Sensor Detection");
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
                    line.getStatus(), line.isBackupLink()
                            ? " (backup; approval=" + line.getBackupApprovalStatus() + ")" : "");
            if (line.getStatus() == LineStatus.FAILED) {
                System.out.println("     Estimated repair: "
                        + line.getRepairTimeMinutes() + " minute(s)");
            }
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
        Integer repairMinutes = requestRepairTime(scanner, line);
        if (repairMinutes != null) {
            processFault(line, "Manual fault selected by user", repairMinutes);
        }
    }

    private void runSensorDetection(Scanner scanner) {
        System.out.println("\nSelect a city event:");
        System.out.println("1. Storm (2-3 residential line tears)");
        System.out.println("2. Peak Load Overload (industrial/commercial safety trip)");
        System.out.println("3. Random Aging Fault (170 V brownout)");
        System.out.print("Enter event type: ");
        if (!scanner.hasNextLine()) {
            return;
        }
        String eventType;
        switch (scanner.nextLine().trim()) {
            case "1":
                eventType = "Storm";
                break;
            case "2":
                eventType = "Peak Load Overload";
                break;
            case "3":
                eventType = "Random Aging Fault";
                break;
            default:
                System.out.println("Invalid event type. No changes made.");
                return;
        }
        List<NetworkLine> eventLines;
        try {
            eventLines = sensorMonitor.simulateCityEvent(eventType);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            System.out.println(exception.getMessage());
            return;
        }
        System.out.println(eventType + " simulated on "
                + eventLines.stream().map(NetworkLine::getId)
                        .collect(java.util.stream.Collectors.joining(", ")));
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
        for (UncheckedIOException failure : sensorMonitor.drainHistoryWriteFailures()) {
            System.err.println("Warning: " + failure.getMessage()
                    + ". Close outage_history.csv in Excel or another application.");
        }
        if (diagnostics.getFaultedLines().isEmpty()) {
            System.out.println("No failed lines or voltage faults detected.");
            return;
        }

        System.out.println("\n============= AUTOMATIC FAULT DETECTION =============");
        for (NetworkLine line : diagnostics.getFaultedLines()) {
            if (restorationPolicy.isPendingFault(line)) {
                continue;
            }
            Integer repairMinutes = requestRepairTime(scanner, line);
            if (repairMinutes == null) {
                line.restoreLine();
                sensorMonitor.clearAnomaly(line.getId());
                continue;
            }
            line.setRepairTimeMinutes(repairMinutes);
            SensorReading reading = sensorMonitor.getReading(line.getId());
            System.out.println("Fault detected on " + line.getId()
                    + " (status: " + line.getStatus() + ")");
            if (reading != null) {
                System.out.printf("Normal Voltage  : %.0f V%n", reading.getNormalVoltage());
                System.out.printf("Observed Voltage: %.0f V%n", reading.getObservedVoltage());
                System.out.println("Observed Current: " + reading.getObservedCurrent() + " A");
                System.out.println("Anomaly reasons  : "
                        + String.join(", ", sensorMonitor.getAbnormalReasons(reading)));
            }
            FaultReport report = new FaultReport(
                    line, orderAffectedNodes(line, diagnostics.getAffectedNodes()));
            restorationPolicy.submitFault(report, network);
            lastFailedLine = line;
            displayFaultReport(report);
            displayProposedBackupLinks(report);
            System.out.println("Fault added to restoration PriorityQueue.");
        }
        lastAffectedNodes = new ArrayList<>(outageDetector.findAffectedNodes(network));
    }

    private Integer requestRepairTime(Scanner scanner, NetworkLine line) {
        while (true) {
            System.out.print("Enter estimated repair time for " + line.getId()
                    + " in minutes (positive whole number): ");
            if (!scanner.hasNextLine()) {
                System.out.println("No repair time entered; no fault was submitted.");
                return null;
            }
            try {
                return RepairTimeInput.parsePositiveMinutes(scanner.nextLine());
            } catch (IllegalArgumentException exception) {
                System.out.println(exception.getMessage() + " Try again.");
            }
        }
    }

    private void processFault(NetworkLine line, String faultSource, int repairMinutes) {
        if (!line.isWorking()) {
            System.out.println("Line " + line.getId() + " is not working; fault was not submitted.");
            return;
        }

        line.failLine(repairMinutes);
        lastFailedLine = line;
        System.out.println(faultSource + ": " + line.getId() + " marked FAILED.");

        lastAffectedNodes = outageDetector.findAffectedNodes(network);

        FaultReport report = new FaultReport(line, orderAffectedNodes(line, lastAffectedNodes));
        restorationPolicy.submitFault(report, network);
        displayFaultReport(report);
        displayProposedBackupLinks(report);
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
        RepairTeam assignedTeam = report.getAssignedTeam();
        System.out.println("Repair Team        : " + (assignedTeam == null
                ? "Unassigned" : assignedTeam.getId() + " - " + assignedTeam.getName()));
        System.out.println("Repair Time        : " + report.getRepairTimeMinutes() + " minute(s)");
        System.out.println("Travel Time        : " + report.getTravelTimeMinutes() + " minute(s)");
        System.out.println("Estimated Total ETA: "
                + report.getEstimatedRestorationTimeMinutes() + " minute(s)");
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
        System.out.println("Line repair status: " + (report.isLineRepairCompleted()
                ? "REPAIRED" : "PENDING"));
        System.out.println("Affected consumers restored: "
                + report.areAffectedConsumersRestored());
        System.out.println("Entire network restored: " + report.isNetworkFullyRestored());
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

    private void displayProposedBackupLinks(FaultReport report) {
        for (BackupLinkProposal proposal : restorationPolicy.getBackupLinkProposals()) {
            if (!proposal.getFailedLineId().equals(report.getFailedLine().getId())
                    || proposal.getApprovalStatus() != BackupApprovalStatus.PROPOSED) {
                continue;
            }
            String consumers = proposal.getAffectedConsumers().stream()
                    .map(NetworkNode::getId)
                    .collect(java.util.stream.Collectors.joining(", "));
            System.out.println("Proposed backup link " + proposal.getBackupLineId()
                    + " for failed line " + proposal.getFailedLineId());
            System.out.println("  Path: " + String.join(" -> ", proposal.getRestorationPath()));
            System.out.println("  Affected consumers it may reconnect: "
                    + proposal.getAffectedConsumerCount() + " across " + consumers);
            System.out.println("  Approval status: " + proposal.getApprovalStatus());
        }
    }

    private void restoreNetwork(Scanner scanner) {
        if (restorationPolicy.getPendingFaultCount() == 0) {
            System.out.println("There are no faults waiting for restoration.");
            return;
        }
        for (BackupLinkProposal proposal : restorationPolicy.getBackupLinkProposals()) {
            if (proposal.getApprovalStatus() != BackupApprovalStatus.PROPOSED) {
                continue;
            }
            String consumers = proposal.getAffectedConsumers().stream()
                    .map(NetworkNode::getId)
                    .collect(java.util.stream.Collectors.joining(", "));
            System.out.println("Backup proposal " + proposal.getBackupLineId()
                    + " | status=" + proposal.getBackupLine().getStatus()
                    + " | path=" + String.join(" -> ", proposal.getRestorationPath())
                    + " | reconnects=" + consumers);
            System.out.print("Approve this backup link? (y/n): ");
            if (!scanner.hasNextLine()) {
                return;
            }
            if (!"y".equalsIgnoreCase(scanner.nextLine().trim())) {
                restorationPolicy.rejectBackupLink(proposal.getBackupLineId());
                System.out.println(proposal.getBackupLineId()
                        + " rejected and remains inactive.");
                continue;
            }
            restorationPolicy.approveBackupLink(proposal.getBackupLineId());
            System.out.println(proposal.getBackupLineId()
                    + " approved. Approval is separate from activation.");
            System.out.print("Activate this approved link in the simulation? (y/n): ");
            if (!scanner.hasNextLine()) {
                return;
            }
            if ("y".equalsIgnoreCase(scanner.nextLine().trim())) {
                int affectedCount = restorationPolicy.activateApprovedBackupLink(
                        proposal.getBackupLineId(), network, outageDetector);
                lastAffectedNodes = outageDetector.findAffectedNodes(network);
            System.out.println("Backup BFS verification complete. Affected consumers remaining: "
                        + affectedCount);
            }
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
        sensorMonitor = createSensorMonitor();
        lastAffectedNodes.clear();
        lastFailedLine = null;
        System.out.println("Network reset: normal lines WORKING, backup lines STANDBY.");
    }

    private SensorMonitor createSensorMonitor() {
        SensorMonitor monitor = new SensorMonitor(20, 30, network);
        for (NetworkLine line : network.getLines()) {
            monitor.addReading(new SensorReading(line.getId(), 230, 10, 230, 10));
        }
        return monitor;
    }
}

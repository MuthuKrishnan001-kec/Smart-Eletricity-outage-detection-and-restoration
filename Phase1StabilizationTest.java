import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Phase1StabilizationTest {
    private final OutageDetector detector = new OutageDetector();

    public static void main(String[] args) {
        Phase1StabilizationTest test = new Phase1StabilizationTest();
        test.testNormalOperationAndGraphShape();
        test.testSingleFeederLineFailure();
        test.testMultipleLineFailuresAndOfflineSubstation();
        test.testLineRestoration();
        test.testMultipleFaultRestorationStatus();
        System.out.println("Phase 1 stabilization checks passed (5/5).");
    }

    private void testNormalOperationAndGraphShape() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        assertEquals(20, network.getNodes().size(), "node count");
        assertEquals(21, network.getLines().size(), "line count");
        assertEquals(18, primaryLineCount(network), "primary line count");
        assertEquals(3, backupLineCount(network), "backup line count");
        assertEquals(2, network.getSubstations().size(), "substation count");
        assertEquals(20, detector.findReachableNodes(network).size(),
                "reachable node count during normal operation");
        assertEquals(3, criticalIds(network.getNodes()).size(),
                "critical facility count");

        Set<String> expectedIds = new HashSet<>(Arrays.asList(
                "S1", "S2", "F1", "F2", "F3", "F4",
                "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8",
                "C9", "C10", "C11", "H1", "E1", "E2"));
        Set<String> actualIds = new HashSet<>();
        for (NetworkNode node : network.getNodes()) {
            actualIds.add(node.getId());
        }
        assertEquals(expectedIds, actualIds, "network node IDs");
        assertEquals(20, new NetworkCanvas(network).getNodeCoordinates().size(),
                "canvas node coordinate count");

        for (NetworkLine line : network.getLines()) {
            assertTrue(network.getIncidentLines(line.getFromNodeId()).contains(line),
                    line.getId() + " missing from its from-node adjacency list");
            assertTrue(network.getIncidentLines(line.getToNodeId()).contains(line),
                    line.getId() + " missing from its to-node adjacency list");
        }
        assertTrue(network.getLine("L12").isBackupLink(), "L12 backup designation");
        assertEquals(LineStatus.STANDBY, network.getLine("L12").getStatus(),
                "standby backup initial status");
        for (String id : Arrays.asList(
                "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8",
                "C9", "C10", "C11")) {
            assertEquals(10, ((ConsumerArea) network.getNode(id)).getPriorityWeight(),
                    id + " general-area priority weight");
        }
        assertEquals(100, ((ConsumerArea) network.getNode("H1")).getPriorityWeight(),
                "hospital priority weight");
        assertEquals(90, ((ConsumerArea) network.getNode("E1")).getPriorityWeight(),
                "emergency-centre priority weight");
        assertEquals(90, ((ConsumerArea) network.getNode("E2")).getPriorityWeight(),
                "additional emergency-centre priority weight");
    }

    private void testSingleFeederLineFailure() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L7");
        failedLine.failLine();
        assertEquals(LineStatus.FAILED, failedLine.getStatus(), "failed line status");

        List<NetworkNode> affected = detector.findAffectedNodes(network);
        assertEquals(new HashSet<>(Arrays.asList("H1")), consumerIds(affected),
                "consumers disconnected by L7 failure");
        assertEquals(Collections.singleton("H1"), criticalIds(affected),
                "critical loads disconnected by L7 failure");
        assertTrue(!network.getNode("H1").isPowered(), "affected hospital powered flag");
        assertTrue(network.getNode("C5").isPowered(), "unaffected residential area powered flag");

        String[][] backupTransferCases = {
            {"L1", "L14"},
            {"L2", "L12"},
            {"L3", "L13"}
        };
        for (String[] transferCase : backupTransferCases) {
            network = DistributionNetwork.createDemonstrationNetwork();
            NetworkLine transferFailedLine = network.getLine(transferCase[0]);
            transferFailedLine.failLine();
            RestorationPolicy policy = new RestorationPolicy();
            FaultReport report = new FaultReport(
                    transferFailedLine, detector.findAffectedNodes(network));
            policy.submitFault(report, network);
            policy.approveBackupLink(transferCase[1]);
            policy.activateApprovedBackupLink(transferCase[1], network, detector);
            assertEquals(20, detector.findReachableNodes(network).size(),
                    transferCase[0] + " failure should reroute through "
                            + transferCase[1]);
        }
    }

    private void testMultipleLineFailuresAndOfflineSubstation() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L1").failLine();
        network.getLine("L2").failLine();

        List<NetworkNode> affected = detector.findAffectedNodes(network);
        assertEquals(new HashSet<>(Arrays.asList(
                        "F1", "F2", "C1", "C2", "C6", "C7",
                        "H1", "C3", "C8", "C9")),
                nodeIds(affected), "isolated branches after L1 and L2 failures");
        assertEquals(new HashSet<>(Arrays.asList(
                        "C1", "C2", "C6", "C7", "H1", "C3", "C8", "C9")),
                consumerIds(affected), "disconnected consumers after multiple failures");

        network.getLine("L1").restoreLine();
        network.getLine("L2").restoreLine();
        network.getNode("S1").setPowered(false);
        affected = detector.findAffectedNodes(network);
        assertTrue(affected.contains(network.getNode("S1")),
                "offline substation should not be a BFS source");
        assertTrue(!affected.contains(network.getNode("S2")),
                "the remaining active substation should remain reachable");
        assertTrue(network.getNode("C4").isPowered(),
                "south branch remains connected to its active substation");
        assertTrue(!network.getNode("C1").isPowered(),
                "north branch is disconnected from its offline substation");
    }

    private void testLineRestoration() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine line = network.getLine("L7");
        line.failLine();
        List<NetworkNode> affectedBeforeRestore = detector.findAffectedNodes(network);
        assertEquals(Collections.singleton("H1"),
                consumerIds(affectedBeforeRestore),
                "affected consumers before restoration");

        FaultReport report = new FaultReport(line, affectedBeforeRestore);
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report);
        assertTrue(policy.restoreNextFault(network, detector),
                "restoration workflow should rerun BFS and restore all nodes");
        assertTrue(report.isRestored(), "fault report restoration status");
        List<NetworkNode> affectedAfterRestore = detector.findAffectedNodes(network);
        assertTrue(affectedAfterRestore.isEmpty(),
                "BFS should clear affected status after line restoration");
        assertTrue(network.getNode("H1").isPowered(),
                "restored hospital powered flag");

        NetworkLine backup = network.getLine("L12");
        backup.failLine();
        backup.restoreLine();
        assertEquals(LineStatus.STANDBY, backup.getStatus(),
                "restoring a backup should return it to standby");
    }

    private void testMultipleFaultRestorationStatus() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        network.getLine("L6").failLine();
        List<NetworkNode> affectedByBothFaults = detector.findAffectedNodes(network);
        FaultReport firstReport = new FaultReport(
                network.getLine("L5"), affectedByBothFaults);
        FaultReport secondReport = new FaultReport(
                network.getLine("L6"), affectedByBothFaults);
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(firstReport, network);
        policy.submitFault(secondReport, network);

        assertTrue(!policy.restoreNextFault(network, detector),
                "the first line repair should leave the second fault affected");
        assertTrue(firstReport.isLineRepairCompleted(),
                "first line repair should be recorded as complete");
        assertTrue(!firstReport.areAffectedConsumersRestored(),
                "first report consumers should remain affected while L6 is failed");
        assertTrue(!firstReport.isNetworkFullyRestored(),
                "network should remain incomplete while L6 is failed");
        assertTrue(!secondReport.isLineRepairCompleted(),
                "second line repair should still be pending");

        assertTrue(policy.restoreNextFault(network, detector),
                "the final repair should restore complete network connectivity");
        assertTrue(secondReport.isLineRepairCompleted(),
                "second line repair should be recorded as complete");
        assertTrue(firstReport.isRestored(),
                "the earlier report should update when its affected consumers return");
        assertTrue(secondReport.areAffectedConsumersRestored(),
                "second report consumers should be restored after the final repair");
        assertTrue(firstReport.isNetworkFullyRestored()
                        && secondReport.isNetworkFullyRestored(),
                "all reports should reflect complete network connectivity");
        assertEquals(network.getNodes().size(), detector.findReachableNodes(network).size(),
                "all network nodes should be reachable after the final repair");
        for (NetworkNode node : network.getNodes()) {
            if (node instanceof ConsumerArea) {
                assertTrue(node.isPowered(),
                        node.getId() + " should be powered after the final repair");
            }
        }
    }

    private static int primaryLineCount(DistributionNetwork network) {
        int count = 0;
        for (NetworkLine line : network.getLines()) {
            if (!line.isBackupLink()) {
                count++;
            }
        }
        return count;
    }

    private static int backupLineCount(DistributionNetwork network) {
        int count = 0;
        for (NetworkLine line : network.getLines()) {
            if (line.isBackupLink()) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> nodeIds(List<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    private static Set<String> consumerIds(List<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            if (node instanceof ConsumerArea) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    private static Set<String> criticalIds(List<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            if (node instanceof CriticalArea) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}

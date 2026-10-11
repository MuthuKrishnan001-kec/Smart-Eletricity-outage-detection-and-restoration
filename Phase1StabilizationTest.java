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
        System.out.println("Phase 1 stabilization checks passed (4/4).");
    }

    private void testNormalOperationAndGraphShape() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        assertEquals(13, network.getNodes().size(), "node count");
        assertEquals(14, network.getLines().size(), "line count");
        assertEquals(13, detector.findReachableNodes(network).size(),
                "reachable node count during normal operation");

        Set<String> expectedIds = new HashSet<>(Arrays.asList(
                "S1", "S2", "F1", "F2", "F3", "F4", "C1",
                "C2", "C3", "C4", "C5", "H1", "E1"));
        Set<String> actualIds = new HashSet<>();
        for (NetworkNode node : network.getNodes()) {
            actualIds.add(node.getId());
        }
        assertEquals(expectedIds, actualIds, "network node IDs");

        for (NetworkLine line : network.getLines()) {
            assertTrue(network.getIncidentLines(line.getFromNodeId()).contains(line),
                    line.getId() + " missing from its from-node adjacency list");
            assertTrue(network.getIncidentLines(line.getToNodeId()).contains(line),
                    line.getId() + " missing from its to-node adjacency list");
        }
        assertTrue(network.getLine("L12").isBackupLink(), "L12 backup designation");
        assertEquals(LineStatus.STANDBY, network.getLine("L12").getStatus(),
                "standby backup initial status");
    }

    private void testSingleFeederLineFailure() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L7");
        failedLine.failLine();
        assertEquals(LineStatus.FAILED, failedLine.getStatus(), "failed line status");

        List<NetworkNode> affected = detector.findAffectedNodes(network);
        assertEquals(Collections.singleton("H1"), consumerIds(affected),
                "consumers disconnected by L7 failure");
        assertEquals(Collections.singleton("H1"), criticalIds(affected),
                "critical loads disconnected by L7 failure");
        assertTrue(!network.getNode("H1").isPowered(), "affected hospital powered flag");
        assertTrue(network.getNode("C3").isPowered(), "unaffected general area powered flag");

        network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L1").failLine();
        assertEquals(new HashSet<>(Arrays.asList("C1", "C2")),
                consumerIds(detector.findAffectedNodes(network)),
                "consumers disconnected by L1 failure before backup activation");
        network.getLine("L14").activateBackup();
        assertEquals(13, detector.findReachableNodes(network).size(),
                "reachable node count after approved backup activation");
    }

    private void testMultipleLineFailuresAndOfflineSubstation() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L2").failLine();
        network.getLine("L3").failLine();

        List<NetworkNode> affected = detector.findAffectedNodes(network);
        assertEquals(new HashSet<>(Arrays.asList("F2", "F3", "C3", "H1", "C4", "E1")),
                nodeIds(affected), "isolated branches after L2 and L3 failures");
        assertEquals(new HashSet<>(Arrays.asList("C3", "H1", "C4", "E1")),
                consumerIds(affected), "disconnected consumers after multiple failures");

        network.getLine("L2").restoreLine();
        network.getLine("L3").restoreLine();
        network.getNode("S1").setPowered(false);
        affected = detector.findAffectedNodes(network);
        assertTrue(affected.contains(network.getNode("S1")),
                "offline substation should not be a BFS source");
        assertTrue(!affected.contains(network.getNode("S2")),
                "the remaining active substation should remain reachable");
        assertTrue(network.getNode("C4").isPowered(),
                "branch connected to the remaining active substation");
        assertTrue(!network.getNode("C1").isPowered(),
                "branch disconnected from the remaining active substation");
    }

    private void testLineRestoration() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine line = network.getLine("L7");
        line.failLine();
        List<NetworkNode> affectedBeforeRestore = detector.findAffectedNodes(network);
        assertEquals(Collections.singleton("H1"), consumerIds(affectedBeforeRestore),
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

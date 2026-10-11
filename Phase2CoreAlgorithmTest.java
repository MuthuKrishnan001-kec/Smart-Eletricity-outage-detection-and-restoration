import java.util.Arrays;
import java.util.Collections;

public class Phase2CoreAlgorithmTest {
    public static void main(String[] args) {
        testIntegratedVoltageFaultDiagnostics();
        testPriorityQueueReordersMutatedReports();
        testBackupPlanAndRepairTime();
        System.out.println("Phase 2 core algorithm checks passed (3/3).");
    }

    private static void testIntegratedVoltageFaultDiagnostics() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor monitor = new SensorMonitor(20, 30);
        monitor.addReading(new SensorReading("L7", 230, 10, 180, 10));
        monitor.addReading(new SensorReading("L5", 230, 10, 230, 20));

        OutageDiagnostics diagnostics =
                new OutageDetector().detectFaults(network, monitor);

        assertTrue(diagnostics.getFaultedLines().contains(network.getLine("L7")),
                "low voltage should fail L7");
        assertEquals(LineStatus.FAILED, network.getLine("L7").getStatus(),
                "voltage fault line status");
        assertTrue(!diagnostics.getFaultedLines().contains(network.getLine("L5")),
                "current-only anomaly must not automatically fail a line");
        assertEquals(Collections.singleton("H1"),
                nodeIds(diagnostics.getDisconnectedConsumerAreas()),
                "disconnected consumer areas");
        assertEquals(Collections.singleton("H1"),
                nodeIds(diagnostics.getAffectedCriticalFacilities()),
                "affected critical facilities");

        network.getLine("L2").failLine();
        OutageDiagnostics statusDiagnostics =
                new OutageDetector().detectFaults(network, new SensorMonitor(20, 30));
        assertTrue(statusDiagnostics.getFaultedLines().contains(network.getLine("L2")),
                "pre-failed lines must be included without sensor readings");
    }

    private static void testPriorityQueueReordersMutatedReports() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport generalFault = new FaultReport(
                network.getLine("L5"),
                Arrays.<NetworkNode>asList(network.getNode("C1"), network.getNode("C2")));
        FaultReport initiallyLowPriority = new FaultReport(
                network.getLine("L6"),
                Collections.<NetworkNode>singletonList(network.getNode("C3")));
        policy.submitFault(generalFault);
        policy.submitFault(initiallyLowPriority);

        assertTrue(policy.peekNextFault() == generalFault,
                "larger consumer count should lead when critical counts tie");
        initiallyLowPriority.setAffectedNodes(
                Collections.<NetworkNode>singletonList(network.getNode("H1")));
        assertTrue(policy.peekNextFault() == initiallyLowPriority,
                "critical-facility mutation should reheap the pending report");
    }

    private static void testBackupPlanAndRepairTime() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L1");
        failedLine.failLine();
        FaultReport report = new FaultReport(
                failedLine, new OutageDetector().findAffectedNodes(network));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report);

        assertTrue(policy.restoreNextFault(network, new OutageDetector()),
                "failed line repair should restore full connectivity");
        assertEquals(LineStatus.WORKING, network.getLine("L14").getStatus(),
                "approved backup line selected by restoration plan");
        assertTrue(report.getEstimatedRepairTimeMinutes() >= failedLine.getRepairTimeMinutes(),
                "repair estimate should include line repair duration");
        assertEquals(RepairTeamStatus.AVAILABLE,
                report.getRepairTeam().getStatus(),
                "completed synchronous repair should release its team");
        assertEquals(network.getNodes().size(),
                new OutageDetector().findReachableNodes(network).size(),
                "full connectivity after backup activation and repair");
    }

    private static java.util.Set<String> nodeIds(
            Iterable<? extends NetworkNode> nodes) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (NetworkNode node : nodes) {
            ids.add(node.getId());
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

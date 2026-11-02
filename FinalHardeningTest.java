import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class FinalHardeningTest {
    private static int scenariosPassed;
    private static int faultUpdateEvents;

    private FinalHardeningTest() {
    }

    public static void main(String[] args) throws Exception {
        run("normal network operation", FinalHardeningTest::testNormalOperation);
        run("single line failure", FinalHardeningTest::testSingleLineFailure);
        run("simultaneous line failures", FinalHardeningTest::testMultipleLineFailures);
        run("critical facility disconnection", FinalHardeningTest::testHospitalDisconnection);
        run("hospital restoration priority", FinalHardeningTest::testHospitalPriority);
        run("emergency centre restoration priority", FinalHardeningTest::testEmergencyCentrePriority);
        run("independent repair team assignment", FinalHardeningTest::testRepairTeamAssignment);
        run("operator repair time and ETA", FinalHardeningTest::testRepairTimeAndEta);
        run("backup link proposal", FinalHardeningTest::testBackupProposal);
        run("backup link rejection", FinalHardeningTest::testBackupRejection);
        run("approval without activation", FinalHardeningTest::testApprovalRemainsInactive);
        run("backup activation recalculates reports", FinalHardeningTest::testBackupRecalculation);
        run("sequential multi-fault repair", FinalHardeningTest::testSequentialRepairs);
        run("complete network restoration", FinalHardeningTest::testCompleteRestoration);
        run("new fault reopens prior network status", FinalHardeningTest::testStatusReopensOnFault);
        run("history persistence", FinalHardeningTest::testHistoryPersistence);
        System.out.println("Final hardening checks passed (" + scenariosPassed + "/"
                + scenariosPassed + " scenarios; " + faultUpdateEvents
                + " fault/update events).");
        if (scenariosPassed < 15 || faultUpdateEvents < 15) {
            throw new AssertionError("Final hardening run must cover 15 scenarios and events.");
        }
    }

    private static void testNormalOperation() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        OutageDetector detector = new OutageDetector();
        RestorationPolicy policy = new RestorationPolicy();
        assertEquals(20, network.getNodes().size(), "node count");
        assertEquals(24, network.getLines().size(), "line count");
        assertEquals(2, network.getSubstations().size(), "substation count");
        assertEquals(3, policy.getRepairTeams().size(), "repair team count");
        assertEquals(20, detector.findReachableNodes(network).size(),
                "all nodes reachable during normal operation");
        assertTrue(policy.isNetworkFullyRestored(network, detector),
                "normal network is fully restored");
    }

    private static void testSingleLineFailure() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        event();
        assertEquals(ids("C1"), consumerIds(new OutageDetector().findAffectedNodes(network)),
                "L5 disconnects C1 only");
    }

    private static void testMultipleLineFailures() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        event();
        network.getLine("L6").failLine();
        event();
        assertEquals(ids("C1", "C2"), consumerIds(
                new OutageDetector().findAffectedNodes(network)),
                "L5 and L6 disconnect their two areas");
    }

    private static void testHospitalDisconnection() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L7").failLine();
        event();
        List<NetworkNode> affected = new OutageDetector().findAffectedNodes(network);
        assertEquals(ids("H1"), consumerIds(affected), "hospital connection outage");
        assertEquals(ids("H1"), criticalIds(affected), "disconnected hospital");
    }

    private static void testHospitalPriority() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        event();
        network.getLine("L7").failLine();
        event();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport residential = report(network, "L5", "C1");
        FaultReport hospital = report(network, "L7", "H1");
        policy.submitFault(residential, network);
        policy.submitFault(hospital, network);
        assertTrue(policy.peekNextFault() == hospital,
                "hospital outage ranks ahead of residential outage");
    }

    private static void testEmergencyCentrePriority() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        event();
        network.getLine("L10").failLine();
        event();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport residential = report(network, "L5", "C1");
        FaultReport emergencyCentre = report(network, "L10", "E1");
        policy.submitFault(residential, network);
        policy.submitFault(emergencyCentre, network);
        assertTrue(policy.peekNextFault() == emergencyCentre,
                "emergency centre outage ranks ahead of residential outage");
        assertEquals(ids("E1"), criticalIds(emergencyCentre.getAffectedNodes()),
                "emergency centre identified as critical");
    }

    private static void testRepairTeamAssignment() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        RestorationPolicy policy = new RestorationPolicy();
        String[] lines = {"L5", "L6", "L11"};
        String[] areas = {"C1", "C2", "C5"};
        Set<RepairTeam> assigned = new HashSet<>();
        for (int index = 0; index < lines.length; index++) {
            network.getLine(lines[index]).failLine();
            event();
            FaultReport report = report(network, lines[index], areas[index]);
            policy.submitFault(report, network);
            assertTrue(report.getAssignedTeam() != null,
                    lines[index] + " is assigned a repair team");
            assigned.add(report.getAssignedTeam());
        }
        assertEquals(3, assigned.size(), "three distinct repair teams assigned");
    }

    private static void testRepairTimeAndEta() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine line = network.getLine("L7");
        line.failLine(47);
        event();
        FaultReport report = report(network, "L7", "H1");
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);
        assertEquals(47, report.getRepairTimeMinutes(), "operator repair duration");
        assertEquals(15, report.getTravelTimeMinutes(), "assigned team travel time");
        assertEquals(62, report.getEstimatedRestorationTimeMinutes(), "total ETA");
    }

    private static void testBackupProposal() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L1").failLine();
        event();
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report(network, "L1", "C1"), network);
        BackupLinkProposal proposal = proposal(policy, "L14");
        assertEquals(BackupApprovalStatus.PROPOSED, proposal.getApprovalStatus(),
                "candidate begins as a proposal");
        assertEquals(LineStatus.STANDBY, network.getLine("L14").getStatus(),
                "proposal stays inactive");
    }

    private static void testBackupRejection() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L1").failLine();
        event();
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report(network, "L1", "C1"), network);
        policy.rejectBackupLink("L14");
        event();
        assertEquals(BackupApprovalStatus.REJECTED,
                network.getLine("L14").getBackupApprovalStatus(), "rejection recorded");
        assertEquals(LineStatus.STANDBY, network.getLine("L14").getStatus(),
                "rejected link remains inactive");
        assertTrue(!new OutageDetector().findReachableNodes(network)
                        .contains(network.getNode("C1")),
                "rejected standby link does not restore BFS connectivity");
        expectIllegalState(() -> policy.activateApprovedBackupLink(
                "L14", network, new OutageDetector()), "rejected link cannot activate");
    }

    private static void testApprovalRemainsInactive() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L1").failLine();
        event();
        OutageDetector detector = new OutageDetector();
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report(network, "L1", "C1"), network);
        policy.approveBackupLink("L14");
        event();
        assertEquals(BackupApprovalStatus.APPROVED,
                network.getLine("L14").getBackupApprovalStatus(), "approval recorded");
        assertEquals(LineStatus.STANDBY, network.getLine("L14").getStatus(),
                "approval alone does not activate link");
        assertTrue(!detector.findReachableNodes(network).contains(network.getNode("C1")),
                "approved standby link does not affect BFS");
    }

    private static void testBackupRecalculation() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L1");
        failedLine.failLine();
        event();
        OutageDetector detector = new OutageDetector();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport report = report(network, "L1", "C1");
        policy.submitFault(report, network);
        policy.approveBackupLink("L14");
        event();
        int affectedConsumerAreas = policy.activateApprovedBackupLink("L14", network, detector);
        event();
        assertEquals(0, affectedConsumerAreas,
                "activation BFS reports no disconnected consumer areas");
        assertEquals(BackupApprovalStatus.ACTIVE,
                network.getLine("L14").getBackupApprovalStatus(), "backup active state");
        assertTrue(network.getNode("C1").isPowered(), "consumer power state recalculated");
        assertTrue(report.areAffectedConsumersRestored(),
                "report updates when its consumers regain connectivity");
        assertTrue(!report.isLineRepairCompleted(),
                "backup activation does not mark the primary line repaired");
        assertTrue(!report.isNetworkFullyRestored(),
                "network remains unrestored while the primary line is failed");
    }

    private static void testSequentialRepairs() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        network.getLine("L5").failLine();
        event();
        network.getLine("L6").failLine();
        event();
        OutageDetector detector = new OutageDetector();
        List<NetworkNode> affected = detector.findAffectedNodes(network);
        FaultReport first = new FaultReport(network.getLine("L5"), affected);
        FaultReport second = new FaultReport(network.getLine("L6"), affected);
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(first, network);
        policy.submitFault(second, network);

        assertTrue(!policy.restoreNextFault(network, detector),
                "first of two repairs cannot close the network outage");
        event();
        assertTrue(first.isLineRepairCompleted(), "L5 repair status is recorded independently");
        assertTrue(!first.areAffectedConsumersRestored(),
                "C2 remains disconnected after repairing L5");
        assertTrue(!second.isLineRepairCompleted(), "L6 remains unrepaired");
        assertTrue(!first.isNetworkFullyRestored() && !second.isNetworkFullyRestored(),
                "neither report marks the network restored after the first repair");

        assertTrue(policy.restoreNextFault(network, detector),
                "second repair restores every consumer and repairs every failed line");
        event();
        assertTrue(second.isLineRepairCompleted(), "L6 repair status is recorded");
        assertTrue(first.areAffectedConsumersRestored()
                        && second.areAffectedConsumersRestored(),
                "all historical reports update after consumers reconnect");
        assertTrue(first.isNetworkFullyRestored() && second.isNetworkFullyRestored(),
                "historical reports update to final network restoration status");
    }

    private static void testCompleteRestoration() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine line = network.getLine("L7");
        line.failLine();
        event();
        OutageDetector detector = new OutageDetector();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport report = new FaultReport(line, detector.findAffectedNodes(network));
        policy.submitFault(report, network);
        assertTrue(policy.restoreNextFault(network, detector),
                "single fault repair completes the network");
        event();
        assertTrue(report.isLineRepairCompleted(), "line repaired state recorded");
        assertTrue(report.areAffectedConsumersRestored(), "affected consumers restored state");
        assertTrue(report.isNetworkFullyRestored(), "network restored state recorded");
        assertTrue(policy.isNetworkFullyRestored(network, detector),
                "shared core reports a fully restored network");
    }

    private static void testStatusReopensOnFault() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        OutageDetector detector = new OutageDetector();
        RestorationPolicy policy = new RestorationPolicy();
        NetworkLine firstLine = network.getLine("L5");
        firstLine.failLine();
        event();
        FaultReport first = new FaultReport(firstLine, detector.findAffectedNodes(network));
        policy.submitFault(first, network);
        assertTrue(policy.restoreNextFault(network, detector), "initial fault restored");
        event();
        assertTrue(first.isNetworkFullyRestored(), "network initially fully restored");

        NetworkLine secondLine = network.getLine("L6");
        secondLine.failLine();
        event();
        policy.submitFault(new FaultReport(secondLine, detector.findAffectedNodes(network)), network);
        assertTrue(!first.isNetworkFullyRestored(),
                "a later fault reopens network status on prior reports");
    }

    private static void testHistoryPersistence() throws IOException {
        Path historyFile = Files.createTempFile("final-hardening-history-", ".csv");
        try {
            DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
            NetworkLine line = network.getLine("L1");
            line.failLine();
            event();
            OutageDetector detector = new OutageDetector();
            HistoryManager manager = new HistoryManager(historyFile);
            RestorationPolicy policy = RestorationPolicy.withHistoryManager(manager);
            FaultReport report = new FaultReport(line, detector.findAffectedNodes(network));
            policy.submitFault(report, network);
            policy.approveBackupLink("L14");
            event();
            policy.activateApprovedBackupLink("L14", network, detector);
            event();
            assertTrue(policy.restoreNextFault(network, detector),
                    "repair after approved backup activation completes network restoration");
            event();

            List<String[]> events = new HistoryManager(historyFile).loadHistory();
            Set<String> types = new HashSet<>();
            for (String[] row : events) {
                types.add(row[1]);
            }
            assertTrue(types.contains("BACKUP_LINK_PROPOSED"), "proposal event persisted");
            assertTrue(types.contains("BACKUP_LINK_APPROVED"), "approval event persisted");
            assertTrue(types.contains("BACKUP_LINK_ACTIVATED"), "activation event persisted");
            assertTrue(types.contains("BACKUP_BFS_VERIFICATION"), "BFS event persisted");
            assertTrue(types.contains("REPAIR_COMPLETED"), "repair event persisted");
            String[] repairEvent = events.get(events.size() - 1);
            assertEquals("REPAIR_COMPLETED", repairEvent[1], "last persisted event");
            assertTrue(repairEvent[5].contains("LINE_REPAIR_COMPLETED")
                            && repairEvent[5].contains("AFFECTED_CONSUMERS_RESTORED")
                            && repairEvent[5].contains("NETWORK_RESTORED"),
                    "history records the three distinct restoration states");
        } finally {
            Files.deleteIfExists(historyFile);
        }
    }

    private static FaultReport report(
            DistributionNetwork network, String lineId, String affectedConsumerId) {
        return new FaultReport(network.getLine(lineId),
                Collections.singletonList(network.getNode(affectedConsumerId)));
    }

    private static BackupLinkProposal proposal(RestorationPolicy policy, String lineId) {
        for (BackupLinkProposal proposal : policy.getBackupLinkProposals()) {
            if (proposal.getBackupLineId().equals(lineId)) {
                return proposal;
            }
        }
        throw new AssertionError("Expected proposal for " + lineId);
    }

    private static void run(String label, CheckedScenario scenario) throws Exception {
        scenario.run();
        scenariosPassed++;
        System.out.println("PASS " + label);
    }

    private static void event() {
        faultUpdateEvents++;
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

    private static Set<String> criticalIds(List<? extends NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            if (node instanceof CriticalArea) {
                ids.add(node.getId());
            }
        }
        return ids;
    }

    private static Set<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static void expectIllegalState(Runnable operation, String description) {
        try {
            operation.run();
        } catch (IllegalStateException expected) {
            return;
        }
        throw new AssertionError(description);
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected
                    + " but got " + actual + ".");
        }
    }

    @FunctionalInterface
    private interface CheckedScenario {
        void run() throws Exception;
    }
}

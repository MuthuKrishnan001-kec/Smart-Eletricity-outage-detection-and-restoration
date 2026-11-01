import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BackupApprovalTest {
    public static void main(String[] args) throws Exception {
        testStandbyLinkIsNeverAutomaticallyActivated();
        testProposedLinkCanBeApproved();
        testApprovedLinkCanBeActivated();
        testUnapprovedLinkCannotBeActivated();
        testRejectedLinkRemainsInactive();
        testFailedBackupCannotBeActivated();
        testActiveLinkCannotBeActivatedTwice();
        testActivationRunsBfsAndUpdatesConsumers();
        testMultipleBackupLinksAreIndependent();
        testPrimaryLineRepairStillWorks();
        testThreeTeamsAndPriorityRemainUnchanged();
        testRepairEtaRemainsAvailable();
        testApprovalEventsArePersisted();
        System.out.println("Backup approval checks passed (13/13).");
    }

    private static void testStandbyLinkIsNeverAutomaticallyActivated() {
        Fixture fixture = fixture("L1");
        NetworkLine backup = fixture.network.getLine("L14");
        assertEquals(LineStatus.STANDBY, backup.getStatus(), "initial standby status");
        assertEquals(BackupApprovalStatus.PROPOSED, backup.getBackupApprovalStatus(),
                "proposal must not imply approval");
        assertTrue(!fixture.detector.findReachableNodes(fixture.network)
                .contains(fixture.network.getNode("C1")),
                "standby link must not change BFS reachability");

        fixture.policy.restoreNextFault(fixture.network, fixture.detector);
        assertEquals(LineStatus.STANDBY, backup.getStatus(),
                "normal repair must not activate a proposed standby link");
        assertEquals(BackupApprovalStatus.STANDBY, backup.getBackupApprovalStatus(),
                "proposal expires after the fault is repaired");
    }

    private static void testProposedLinkCanBeApproved() {
        Fixture fixture = fixture("L1");
        BackupLinkProposal proposal = proposal(fixture, "L14");
        assertEquals(BackupApprovalStatus.PROPOSED, proposal.getApprovalStatus(),
                "proposal status before operator action");
        assertTrue(proposal.getRestorationPath().contains("L14"),
                "proposed path includes the backup line");
        assertEquals("C1", proposal.getRestorationPath().get(
                proposal.getRestorationPath().size() - 1), "proposed path ends at a consumer");
        fixture.policy.approveBackupLink("L14");
        assertEquals(BackupApprovalStatus.APPROVED, proposal.getApprovalStatus(),
                "operator approval status");
        assertEquals(LineStatus.STANDBY, proposal.getBackupLine().getStatus(),
                "approval alone does not energize the line");
    }

    private static void testApprovedLinkCanBeActivated() {
        Fixture fixture = fixture("L1");
        fixture.policy.approveBackupLink("L14");
        fixture.policy.activateApprovedBackupLink("L14", fixture.network, fixture.detector);
        assertEquals(BackupApprovalStatus.ACTIVE,
                fixture.network.getLine("L14").getBackupApprovalStatus(),
                "activated approval state");
        assertEquals(LineStatus.WORKING, fixture.network.getLine("L14").getStatus(),
                "activated line operational state");
    }

    private static void testUnapprovedLinkCannotBeActivated() {
        Fixture fixture = fixture("L1");
        expectIllegalState(() -> fixture.policy.activateApprovedBackupLink(
                "L14", fixture.network, fixture.detector),
                "unapproved proposed link must be rejected");
        expectIllegalState(() -> fixture.network.getLine("L14").activateBackup(),
                "direct activation of an unapproved link must be rejected");
        expectIllegalState(() -> fixture.network.getLine("L2").activateBackup(),
                "a primary line cannot be activated as a backup");
        assertEquals(LineStatus.STANDBY, fixture.network.getLine("L14").getStatus(),
                "unapproved link remains inactive");
    }

    private static void testRejectedLinkRemainsInactive() {
        Fixture fixture = fixture("L1");
        fixture.policy.rejectBackupLink("L14");
        assertEquals(BackupApprovalStatus.REJECTED,
                fixture.network.getLine("L14").getBackupApprovalStatus(),
                "operator rejection state");
        assertEquals(LineStatus.STANDBY, fixture.network.getLine("L14").getStatus(),
                "rejected link stays inactive");
        expectIllegalState(() -> fixture.policy.activateApprovedBackupLink(
                "L14", fixture.network, fixture.detector),
                "rejected link cannot be activated");
    }

    private static void testFailedBackupCannotBeActivated() {
        Fixture fixture = fixture("L1");
        NetworkLine backup = fixture.network.getLine("L14");
        backup.failLine();
        assertEquals(BackupApprovalStatus.FAILED, backup.getBackupApprovalStatus(),
                "failed backup approval state");
        expectIllegalState(() -> fixture.policy.activateApprovedBackupLink(
                "L14", fixture.network, fixture.detector),
                "failed backup link must not activate");
        expectIllegalState(() -> fixture.policy.approveBackupLink("L14"),
                "failed backup link must not be approved");
    }

    private static void testActiveLinkCannotBeActivatedTwice() {
        Fixture fixture = fixture("L1");
        fixture.policy.approveBackupLink("L14");
        fixture.policy.activateApprovedBackupLink("L14", fixture.network, fixture.detector);
        expectIllegalState(() -> fixture.policy.activateApprovedBackupLink(
                "L14", fixture.network, fixture.detector),
                "already active link must not be activated a second time");
    }

    private static void testActivationRunsBfsAndUpdatesConsumers() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L1");
        failedLine.failLine();
        TrackingDetector detector = new TrackingDetector();
        FaultReport report = new FaultReport(failedLine, detector.findAffectedNodes(network));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);
        policy.approveBackupLink("L14");
        int bfsBefore = detector.bfsRuns;
        int affectedAfterActivation = policy.activateApprovedBackupLink(
                "L14", network, detector);
        assertTrue(detector.bfsRuns > bfsBefore,
                "activation must run outage BFS verification");
        assertEquals(0, affectedAfterActivation, "affected nodes after backup activation");
        assertTrue(network.getNode("C1").isPowered(),
                "reconnected consumer powered state must be refreshed");
        assertTrue(network.getNode("C6").isPowered(),
                "all consumers on the restored feeder must be powered");
    }

    private static void testMultipleBackupLinksAreIndependent() {
        DistributionNetwork network = new DistributionNetwork();
        network.addNode(new Substation("S1", "North Source", true));
        network.addNode(new Substation("S2", "South Source", true));
        network.addNode(new Feeder("F1", "Disconnected Feeder", true));
        network.addNode(new GeneralArea("C1", "Consumer One", true, 1));
        network.addLine(new NetworkLine("P1", "S1", "F1", false, 10));
        network.addLine(new NetworkLine("P2", "F1", "C1", false, 10));
        network.addLine(new NetworkLine("B1", "S2", "F1", true, 10));
        network.addLine(new NetworkLine("B2", "S2", "C1", true, 10));
        network.getLine("P1").failLine();
        OutageDetector detector = new OutageDetector();
        FaultReport report = new FaultReport(
                network.getLine("P1"), detector.findAffectedNodes(network));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);

        assertEquals(2, policy.getBackupLinkProposals().size(),
                "independent candidate links proposed");
        policy.approveBackupLink("B1");
        assertEquals(BackupApprovalStatus.APPROVED,
                network.getLine("B1").getBackupApprovalStatus(), "B1 approval");
        assertEquals(BackupApprovalStatus.PROPOSED,
                network.getLine("B2").getBackupApprovalStatus(),
                "B2 remains independently proposed");
        policy.activateApprovedBackupLink("B1", network, detector);
        assertEquals(BackupApprovalStatus.ACTIVE,
                network.getLine("B1").getBackupApprovalStatus(), "B1 active state");
        assertEquals(LineStatus.STANDBY, network.getLine("B2").getStatus(),
                "B2 remains inactive");
    }

    private static void testPrimaryLineRepairStillWorks() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L5");
        failedLine.failLine(27);
        OutageDetector detector = new OutageDetector();
        FaultReport report = new FaultReport(failedLine, detector.findAffectedNodes(network));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);
        policy.restoreNextFault(network, detector);
        assertEquals(LineStatus.WORKING, failedLine.getStatus(),
                "primary line restored by repair");
        assertTrue(report.isLineRepairCompleted(), "primary repair completion recorded");
        assertTrue(network.getNode("C1").isPowered(), "consumer powered after primary repair");
    }

    private static void testThreeTeamsAndPriorityRemainUnchanged() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        RestorationPolicy policy = new RestorationPolicy();
        assertEquals(3, policy.getRepairTeams().size(), "default repair team count");
        assertEquals(Arrays.asList("Alpha", "Bravo", "Charlie"), teamNames(policy),
                "default repair team names and order");

        NetworkLine residentialLine = network.getLine("L5");
        NetworkLine hospitalLine = network.getLine("L7");
        residentialLine.failLine();
        hospitalLine.failLine();
        FaultReport residential = new FaultReport(
                residentialLine, Collections.<NetworkNode>singletonList(network.getNode("C1")));
        FaultReport hospital = new FaultReport(
                hospitalLine, Collections.<NetworkNode>singletonList(network.getNode("H1")));
        policy.submitFault(residential, network);
        policy.submitFault(hospital, network);
        assertTrue(policy.peekNextFault() == hospital,
                "critical-facility priority ordering remains unchanged");
        assertTrue(residential.getAssignedTeam() != hospital.getAssignedTeam(),
                "repair teams remain separately allocated");
    }

    private static void testRepairEtaRemainsAvailable() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine("L7");
        failedLine.failLine(47);
        FaultReport report = new FaultReport(
                failedLine, Collections.<NetworkNode>singletonList(network.getNode("H1")));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);
        assertEquals(47, report.getRepairTimeMinutes(), "operator-entered repair time");
        assertEquals(15, report.getTravelTimeMinutes(), "separate travel ETA");
        assertEquals(62, report.getEstimatedRestorationTimeMinutes(),
                "repair-time plus travel ETA");
    }

    private static void testApprovalEventsArePersisted() throws IOException {
        Path historyFile = Files.createTempFile("backup-approval-history-", ".csv");
        try {
            DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
            NetworkLine failedLine = network.getLine("L1");
            failedLine.failLine();
            OutageDetector detector = new OutageDetector();
            FaultReport report = new FaultReport(failedLine, detector.findAffectedNodes(network));
            HistoryManager manager = new HistoryManager(historyFile);
            RestorationPolicy policy = RestorationPolicy.withHistoryManager(manager);
            policy.submitFault(report, network);
            policy.approveBackupLink("L14");
            policy.activateApprovedBackupLink("L14", network, detector);

            Set<String> eventTypes = new HashSet<>();
            for (String[] event : manager.loadHistory()) {
                eventTypes.add(event[1]);
                if (event[1].startsWith("BACKUP_")) {
                    assertEquals("L14", event[2], "backup event line ID");
                    assertTrue(!event[0].trim().isEmpty(), "backup event timestamp");
                }
            }
            assertTrue(eventTypes.contains("BACKUP_LINK_PROPOSED"), "proposal event persisted");
            assertTrue(eventTypes.contains("BACKUP_LINK_APPROVED"), "approval event persisted");
            assertTrue(eventTypes.contains("BACKUP_LINK_ACTIVATED"), "activation event persisted");
            assertTrue(eventTypes.contains("BACKUP_BFS_VERIFICATION"), "BFS event persisted");
        } finally {
            Files.deleteIfExists(historyFile);
        }
    }

    private static Fixture fixture(String failedLineId) {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine failedLine = network.getLine(failedLineId);
        failedLine.failLine();
        OutageDetector detector = new OutageDetector();
        FaultReport report = new FaultReport(failedLine, detector.findAffectedNodes(network));
        RestorationPolicy policy = new RestorationPolicy();
        policy.submitFault(report, network);
        return new Fixture(network, detector, report, policy);
    }

    private static BackupLinkProposal proposal(Fixture fixture, String lineId) {
        for (BackupLinkProposal proposal : fixture.policy.getBackupLinkProposals()) {
            if (proposal.getBackupLineId().equals(lineId)) {
                return proposal;
            }
        }
        throw new AssertionError("Expected backup proposal for " + lineId + ".");
    }

    private static List<String> teamNames(RestorationPolicy policy) {
        List<String> names = new ArrayList<>();
        for (RepairTeam team : policy.getRepairTeams()) {
            names.add(team.getName());
        }
        return names;
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

    private static final class Fixture {
        private final DistributionNetwork network;
        private final OutageDetector detector;
        private final FaultReport report;
        private final RestorationPolicy policy;

        private Fixture(DistributionNetwork network, OutageDetector detector,
                FaultReport report, RestorationPolicy policy) {
            this.network = network;
            this.detector = detector;
            this.report = report;
            this.policy = policy;
        }
    }

    private static final class TrackingDetector extends OutageDetector {
        private int bfsRuns;

        @Override
        public Set<NetworkNode> findReachableNodes(DistributionNetwork network) {
            bfsRuns++;
            return super.findReachableNodes(network);
        }
    }
}

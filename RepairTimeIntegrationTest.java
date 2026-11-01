import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class RepairTimeIntegrationTest {
    public static void main(String[] args) {
        testPositiveIntegerInputValidation();
        testThreeIndependentRepairEstimatesAndEta();
        testRepairRestoresLineStatus();
        System.out.println("Repair-time integration checks passed (3/3).");
    }

    private static void testPositiveIntegerInputValidation() {
        assertEquals(20, RepairTimeInput.parsePositiveMinutes("20"),
                "positive whole minutes should be accepted");
        assertRejected("0", "zero repair time");
        assertRejected("-5", "negative repair time");
        assertRejected("", "empty repair time");
        assertRejected("twenty", "text repair time");
        assertRejected("2.5", "fractional repair time");
        assertRejected("2147483648", "repair time beyond integer range");
    }

    private static void testThreeIndependentRepairEstimatesAndEta() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine line5 = network.getLine("L5");
        NetworkLine line8 = network.getLine("L8");
        NetworkLine line11 = network.getLine("L11");
        int unrelatedLineRepairTime = network.getLine("L7").getRepairTimeMinutes();

        line5.failLine(20);
        line8.failLine(45);
        line11.failLine(90);
        assertEquals(20, line5.getRepairTimeMinutes(), "L5 repair duration");
        assertEquals(45, line8.getRepairTimeMinutes(), "L8 repair duration");
        assertEquals(90, line11.getRepairTimeMinutes(), "L11 repair duration");
        assertEquals(unrelatedLineRepairTime, network.getLine("L7").getRepairTimeMinutes(),
                "repair-time entry must not change an unrelated line");

        OutageDetector detector = new OutageDetector();
        List<FaultReport> reports = new ArrayList<>();
        reports.add(new FaultReport(line5, detector.findAffectedNodes(network)));
        reports.add(new FaultReport(line8, detector.findAffectedNodes(network)));
        reports.add(new FaultReport(line11, detector.findAffectedNodes(network)));
        RestorationPolicy policy = new RestorationPolicy();
        for (FaultReport report : reports) {
            policy.submitFault(report, network);
        }

        Set<RepairTeam> assignedTeams = new HashSet<>();
        for (FaultReport report : reports) {
            int repairMinutes = report.getFailedLine().getRepairTimeMinutes();
            assertEquals(repairMinutes, report.getRepairTimeMinutes(),
                    report.getFailedLine().getId() + " report repair duration");
            assertEquals(15, report.getTravelTimeMinutes(),
                    report.getFailedLine().getId() + " separate travel duration");
            assertEquals(repairMinutes + 15, report.getEstimatedRestorationTimeMinutes(),
                    report.getFailedLine().getId() + " total restoration estimate");
            assertTrue(assignedTeams.add(report.getAssignedTeam()),
                    "simultaneous faults must use distinct teams");
        }

        RepairTeam localTeam = new RepairTeam(
                "RT-LOCAL", "Local Crew", "Local Depot", CityDistrict.SOUTH);
        NetworkLine exampleLine = new NetworkLine(
                "ETA", "S1", "F1", false, 20, CityDistrict.SOUTH);
        assertEquals(15, policy.estimateTravelTimeMinutes(localTeam, exampleLine),
                "travel component for the example estimate");
        assertEquals(35, policy.estimateRepairTimeMinutes(network, localTeam, exampleLine),
                "total ETA must use the entered repair time plus travel time");
    }

    private static void testRepairRestoresLineStatus() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkLine primaryLine = network.getLine("L5");
        primaryLine.failLine(37);
        primaryLine.repair();
        assertEquals(LineStatus.WORKING, primaryLine.getStatus(),
                "repaired primary line status");
        assertEquals(37, primaryLine.getRepairTimeMinutes(),
                "repair estimate remains associated with its line after repair");

        NetworkLine backupLine = network.getLine("L12");
        backupLine.failLine(18);
        backupLine.repair();
        assertEquals(LineStatus.STANDBY, backupLine.getStatus(),
                "repaired backup line returns to standby");
    }

    private static void assertRejected(String value, String description) {
        try {
            RepairTimeInput.parsePositiveMinutes(value);
            throw new AssertionError(description + " should be rejected.");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected
                    + " but got " + actual + ".");
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }
}

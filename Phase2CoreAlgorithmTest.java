import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class Phase2CoreAlgorithmTest {
    public static void main(String[] args) {
        testIntegratedVoltageFaultDiagnostics();
        testHighVoltageFaultDiagnostics();
        testPriorityQueueOrdering();
        testPriorityChangesReorderQueue();
        testHighPriorityReportPreemptsWaitingAssignments();
        testBackupPlanAndRepairTime();
        testCityEventsAndCrewAssignment();
        System.out.println("Phase 2 core algorithm checks passed (7/7).");
    }

    private static void testIntegratedVoltageFaultDiagnostics() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor monitor = new SensorMonitor(20, 30, network);
        monitor.addReading(new SensorReading("L7", 230, 10, 180, 10));
        monitor.addReading(new SensorReading("L10", 230, 10, 230, 13));

        OutageDiagnostics diagnostics =
                new OutageDetector().detectFaults(network, monitor);

        assertTrue(diagnostics.getFaultedLines().contains(network.getLine("L7")),
                "low voltage should fail L7");
        assertEquals(LineStatus.FAILED, network.getLine("L7").getStatus(),
                "voltage fault line status");
        assertTrue(!diagnostics.getFaultedLines().contains(network.getLine("L10")),
                "an untripped current-only anomaly must not fail a line");
        assertEquals(Collections.singleton("H1"),
                nodeIds(diagnostics.getAffectedCriticalFacilities()),
                "disconnected critical facilities");

        network.getLine("L2").failLine();
        OutageDiagnostics statusDiagnostics =
                new OutageDetector().detectFaults(network, new SensorMonitor(20, 30));
        assertTrue(statusDiagnostics.getFaultedLines().contains(network.getLine("L2")),
                "pre-failed lines must be included without sensor readings");
    }

    private static void testHighVoltageFaultDiagnostics() {
        assertTrue(!new SensorReading("L1", 230, 10, 250, 10)
                .isVoltageAbnormal(20), "250 V is not above the cutoff threshold");
        assertTrue(new SensorReading("L1", 230, 10, 250.1, 10)
                .isVoltageAbnormal(20), "voltage above 250 V is abnormal");
        assertTrue(new SensorReading("L1", 230, 10, 250.1, 10)
                .getAbnormalReasons(20, 30).contains("Voltage exceeded high-voltage cutoff"),
                "overvoltage diagnostic reason");

        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor monitor = new SensorMonitor(20, 30, network);
        monitor.addReading(new SensorReading("L7", 230, 10, 250, 10));
        Set<NetworkLine> atThreshold = monitor.findFaultedLines(network.getLines());
        assertTrue(!atThreshold.contains(network.getLine("L7")),
                "line does not trip at exactly 250 V");

        monitor.addReading(new SensorReading("L7", 230, 10, 251, 10));
        Set<NetworkLine> aboveThreshold = monitor.findFaultedLines(network.getLines());
        assertTrue(aboveThreshold.contains(network.getLine("L7")),
                "line trips above 250 V");
        assertEquals(LineStatus.FAILED, network.getLine("L7").getStatus(),
                "overvoltage trip line status");
    }

    private static void testPriorityQueueOrdering() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        FaultReport hospitalFault = new FaultReport(
                network.getLine("L7"), Collections.<NetworkNode>singletonList(
                        network.getNode("H1")));
        FaultReport residentialFault = new FaultReport(
                network.getLine("L5"), Collections.<NetworkNode>singletonList(
                        new GeneralArea("R1", "Large Residential Area", true, 10000)));
        RestorationPolicy hospitalPolicy = new RestorationPolicy();
        hospitalPolicy.submitFault(residentialFault);
        hospitalPolicy.submitFault(hospitalFault);
        assertTrue(hospitalPolicy.peekNextFault() == hospitalFault,
                "a hospital fault must outrank a residential fault");

        FaultReport twoCriticalFacilities = new FaultReport(
                network.getLine("L5"), Arrays.<NetworkNode>asList(
                        network.getNode("H1"), network.getNode("E1")));
        FaultReport oneCriticalFacility = new FaultReport(
                network.getLine("L6"), Arrays.<NetworkNode>asList(
                        network.getNode("H1"),
                        new GeneralArea("R2", "Large Residential Area", true, 10000)));
        RestorationPolicy criticalCountPolicy = new RestorationPolicy();
        criticalCountPolicy.submitFault(oneCriticalFacility);
        criticalCountPolicy.submitFault(twoCriticalFacilities);
        assertTrue(criticalCountPolicy.peekNextFault() == twoCriticalFacilities,
                "higher critical-facility count must rank first");

        NetworkNode industrial = new ZonedArea(
                "I1", "Industrial Area", true, 100,
                ConsumerZone.INDUSTRIAL, CityDistrict.SOUTH);
        NetworkNode residential = new GeneralArea(
                "R3", "Residential Area", true, 200);
        FaultReport fewerConsumers = new FaultReport(
                network.getLine("L5"), Collections.singletonList(industrial));
        FaultReport moreConsumers = new FaultReport(
                network.getLine("L6"), Collections.singletonList(residential));
        RestorationPolicy consumerCountPolicy = new RestorationPolicy();
        consumerCountPolicy.submitFault(fewerConsumers);
        consumerCountPolicy.submitFault(moreConsumers);
        assertTrue(consumerCountPolicy.peekNextFault() == moreConsumers,
                "total affected consumers must outrank industrial weighting");

        FaultReport firstInTie = new FaultReport(
                network.getLine("L5"), Collections.<NetworkNode>singletonList(
                        new GeneralArea("R4", "First Area", true, 50)));
        FaultReport secondInTie = new FaultReport(
                network.getLine("L6"), Collections.<NetworkNode>singletonList(
                        new GeneralArea("R5", "Second Area", true, 50)));
        RestorationPolicy reportOrderPolicy = new RestorationPolicy();
        reportOrderPolicy.submitFault(secondInTie);
        reportOrderPolicy.submitFault(firstInTie);
        assertTrue(reportOrderPolicy.peekNextFault() == firstInTie,
                "equal impact must use earliest report sequence even if submitted later");
    }

    private static void testPriorityChangesReorderQueue() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        RestorationPolicy policy = new RestorationPolicy();
        FaultReport updatedReport = new FaultReport(
                network.getLine("L5"), Collections.<NetworkNode>singletonList(
                        new GeneralArea("R6", "Small Area", true, 1)));
        FaultReport otherReport = new FaultReport(
                network.getLine("L6"), Collections.<NetworkNode>singletonList(
                        new GeneralArea("R7", "Medium Area", true, 10)));
        policy.submitFault(updatedReport);
        policy.submitFault(otherReport);
        updatedReport.setAffectedNodes(Collections.<NetworkNode>singletonList(
                network.getNode("H1")));
        assertTrue(policy.peekNextFault() == updatedReport,
                "a queued report must be reinserted after its priority changes");
    }

    private static void testHighPriorityReportPreemptsWaitingAssignments() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        List<RepairTeam> teams = Arrays.asList(
                new RepairTeam("RT-A", "Alpha"),
                new RepairTeam("RT-B", "Bravo"),
                new RepairTeam("RT-C", "Charlie"));
        RestorationPolicy policy = new RestorationPolicy(teams);
        OutageDetector detector = new OutageDetector();
        NetworkLine firstLowerLine = network.getLine("L5");
        firstLowerLine.failLine();
        FaultReport firstLowerPriority = new FaultReport(
                firstLowerLine, detector.findAffectedNodes(network));
        NetworkLine secondLowerLine = network.getLine("L6");
        secondLowerLine.failLine();
        FaultReport secondLowerPriority = new FaultReport(
                secondLowerLine, detector.findAffectedNodes(network));
        NetworkLine thirdLowerLine = network.getLine("L9");
        thirdLowerLine.failLine();
        FaultReport thirdLowerPriority = new FaultReport(
                thirdLowerLine, detector.findAffectedNodes(network));
        policy.submitFault(firstLowerPriority, network);
        policy.submitFault(secondLowerPriority, network);
        policy.submitFault(thirdLowerPriority, network);
        assertDistinctAssignedTeams(
                Arrays.asList(firstLowerPriority, secondLowerPriority, thirdLowerPriority),
                "three lower-priority jobs must reserve separate teams");

        NetworkLine hospitalLine = network.getLine("L7");
        hospitalLine.failLine();
        FaultReport hospitalReport = new FaultReport(
                hospitalLine, detector.findAffectedNodes(network));
        policy.submitFault(hospitalReport, network);
        assertTrue(policy.peekNextFault() == hospitalReport,
                "new hospital report must become the highest-priority pending job");
        assertTrue(hospitalReport.getAssignedTeam() != null,
                "highest-priority pending job must receive a reserved team");

        policy.restoreNextFault(network, detector);
        assertTrue(hospitalReport.isLineRepairCompleted(),
                "hospital report must dispatch before the three already-assigned jobs");
        assertEquals(3, policy.getPendingFaultCount(),
                "only the hospital report should be removed from the pending queue");
    }

    private static void testBackupPlanAndRepairTime() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        RestorationPolicy policy = new RestorationPolicy();
        RepairTeam northTeam = new RepairTeam(
                "RT-N", "North Crew", "North Depot", CityDistrict.NORTH);
        RepairTeam centralTeam = new RepairTeam(
                "RT-C", "Central Crew", "Central Depot", CityDistrict.DOWNTOWN);
        RepairTeam southTeam = new RepairTeam(
                "RT-S", "South Crew", "South Depot", CityDistrict.SOUTH);
        NetworkLine southLine = new NetworkLine(
                "ETA", "S1", "F1", false, 45, CityDistrict.SOUTH);
        assertEquals(85, policy.estimateRepairTimeMinutes(network, northTeam, southLine),
                "opposite-district ETA");
        assertEquals(70, policy.estimateRepairTimeMinutes(network, centralTeam, southLine),
                "adjacent-district ETA");
        assertEquals(60, policy.estimateRepairTimeMinutes(network, southTeam, southLine),
                "same-district ETA");

        NetworkLine failedLine = network.getLine("L7");
        failedLine.failLine();
        FaultReport report = new FaultReport(
                failedLine, new OutageDetector().findAffectedNodes(network));
        RestorationPolicy restorationPolicy = new RestorationPolicy();
        restorationPolicy.submitFault(report, network);
        assertEquals(50, report.getEstimatedRepairMinutes(),
                "local depot ETA must include L7's 35-minute repair and 15-minute travel");
        assertTrue(restorationPolicy.restoreNextFault(network, new OutageDetector()),
                "failed line repair should restore full connectivity");
        assertTrue(report.isRestored(), "restored report state");
        assertEquals(RepairTeamStatus.AVAILABLE,
                report.getRepairTeam().getStatus(),
                "completed repair should release its team");
        assertEquals(network.getNodes().size(),
                new OutageDetector().findReachableNodes(network).size(),
                "full connectivity after backup activation and repair");
    }

    private static void testCityEventsAndCrewAssignment() {
        DistributionNetwork stormNetwork = DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor stormMonitor = createMonitor(stormNetwork);
        List<NetworkLine> stormLines = stormMonitor.simulateCityEvent("Storm");
        assertTrue(stormLines.size() >= 2 && stormLines.size() <= 3,
                "storm must affect two or three residential lines");
        for (NetworkLine line : stormLines) {
            assertEquals(0.0,
                    stormMonitor.getReading(line.getId()).getObservedVoltage(),
                    "storm tear voltage");
            assertTrue(hasResidentialEndpoint(stormNetwork, line),
                    "storm selection must be residential");
        }
        assertEquals(stormLines.size(),
                stormMonitor.findFaultedLines(stormNetwork.getLines()).size(),
                "storm tears must trip their lines");

        DistributionNetwork overloadNetwork =
                DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor overloadMonitor = createMonitor(overloadNetwork);
        NetworkLine overloadLine =
                overloadMonitor.simulateCityEvent("Peak Load Overload").get(0);
        SensorReading overloadReading = overloadMonitor.getReading(overloadLine.getId());
        assertEquals(overloadReading.getNormalCurrent() * 1.35,
                overloadReading.getObservedCurrent(), "peak-load current spike");
        assertTrue(hasEconomicEndpoint(overloadNetwork, overloadLine),
                "overload selection must be industrial or commercial");
        assertTrue(overloadMonitor.findFaultedLines(overloadNetwork.getLines())
                        .contains(overloadLine),
                "peak-load overload must trigger a safety trip");

        DistributionNetwork agingNetwork = DistributionNetwork.createDemonstrationNetwork();
        SensorMonitor agingMonitor = createMonitor(agingNetwork);
        NetworkLine agingLine =
                agingMonitor.simulateCityEvent("Random Aging Fault").get(0);
        assertEquals(170.0,
                agingMonitor.getReading(agingLine.getId()).getObservedVoltage(),
                "aging fault brownout voltage");
        assertTrue(agingMonitor.findFaultedLines(agingNetwork.getLines()).contains(agingLine),
                "aging brownout must trip its line");

        RestorationPolicy assignmentPolicy = new RestorationPolicy();
        FaultReport firstReport = new FaultReport(
                stormNetwork.getLine("L5"), Collections.emptyList());
        FaultReport secondReport = new FaultReport(
                stormNetwork.getLine("L7"), Collections.emptyList());
        FaultReport thirdReport = new FaultReport(
                stormNetwork.getLine("L9"), Collections.emptyList());
        assignmentPolicy.submitFault(firstReport, stormNetwork);
        assignmentPolicy.submitFault(secondReport, stormNetwork);
        assignmentPolicy.submitFault(thirdReport, stormNetwork);
        assertDistinctAssignedTeams(
                Arrays.asList(firstReport, secondReport, thirdReport),
                "three repair teams must be assigned to separate jobs");
        assertEquals("Alpha", firstReport.getAssignedTeam().getName(),
                "first available repair team");
        assertEquals("Bravo", secondReport.getAssignedTeam().getName(),
                "second available repair team");
        assertEquals("Charlie", thirdReport.getAssignedTeam().getName(),
                "third available repair team");
    }

    private static void assertDistinctAssignedTeams(
            List<FaultReport> reports, String message) {
        java.util.Set<RepairTeam> assignedTeams = new java.util.HashSet<>();
        for (FaultReport report : reports) {
            assertTrue(report.getAssignedTeam() != null,
                    message + " (job is unassigned)");
            assertTrue(assignedTeams.add(report.getAssignedTeam()),
                    message + " (team assigned more than once)");
            assertEquals(RepairTeamStatus.BUSY, report.getAssignedTeam().getStatus(),
                    message + " (reserved team must be busy)");
        }
    }

    private static SensorMonitor createMonitor(DistributionNetwork network) {
        SensorMonitor monitor = new SensorMonitor(20, 30, network);
        for (NetworkLine line : network.getLines()) {
            monitor.addReading(new SensorReading(line.getId(), 230, 10, 230, 10));
        }
        return monitor;
    }

    private static boolean hasResidentialEndpoint(
            DistributionNetwork network, NetworkLine line) {
        return network.getNode(line.getFromNodeId()) instanceof GeneralArea
                || network.getNode(line.getToNodeId()) instanceof GeneralArea
                || isZone(network.getNode(line.getFromNodeId()), ConsumerZone.RESIDENTIAL)
                || isZone(network.getNode(line.getToNodeId()), ConsumerZone.RESIDENTIAL);
    }

    private static boolean hasEconomicEndpoint(
            DistributionNetwork network, NetworkLine line) {
        return network.getNode(line.getFromNodeId()) instanceof GeneralArea
                || network.getNode(line.getToNodeId()) instanceof GeneralArea
                || isZone(network.getNode(line.getFromNodeId()), ConsumerZone.INDUSTRIAL)
                || isZone(network.getNode(line.getToNodeId()), ConsumerZone.INDUSTRIAL)
                || isZone(network.getNode(line.getFromNodeId()), ConsumerZone.COMMERCIAL)
                || isZone(network.getNode(line.getToNodeId()), ConsumerZone.COMMERCIAL);
    }

    private static boolean isZone(NetworkNode node, ConsumerZone zone) {
        return node instanceof ZonedArea && ((ZonedArea) node).getZone() == zone;
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

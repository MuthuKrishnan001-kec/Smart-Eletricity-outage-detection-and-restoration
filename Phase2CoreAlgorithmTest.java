import java.util.Collections;
import java.util.List;

public class Phase2CoreAlgorithmTest {
    public static void main(String[] args) {
        testIntegratedVoltageFaultDiagnostics();
        testPriorityQueueOrdering();
        testBackupPlanAndRepairTime();
        testCityEventsAndCrewAssignment();
        System.out.println("Phase 2 core algorithm checks passed (4/4).");
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

    private static void testPriorityQueueOrdering() {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        NetworkNode industrial = new ZonedArea(
                "I1", "Industrial Area", true, 1,
                ConsumerZone.INDUSTRIAL, CityDistrict.SOUTH);
        NetworkNode residential = new GeneralArea("R1", "Large Residential Area", true, 10000);

        RestorationPolicy economicPolicy = new RestorationPolicy();
        FaultReport manyHouseholds = new FaultReport(
                network.getLine("L5"), Collections.singletonList(residential));
        FaultReport industrialFault = new FaultReport(
                network.getLine("L6"), Collections.singletonList(industrial));
        economicPolicy.submitFault(manyHouseholds);
        economicPolicy.submitFault(industrialFault);
        assertTrue(economicPolicy.peekNextFault() == industrialFault,
                "industrial/commercial impact must rank above residential household count");

        RestorationPolicy householdPolicy = new RestorationPolicy();
        FaultReport fewerHouseholds = new FaultReport(
                network.getLine("L5"),
                Collections.<NetworkNode>singletonList(
                        new GeneralArea("R2", "Smaller Residential Area", true, 1000)));
        FaultReport moreHouseholds = new FaultReport(
                network.getLine("L6"),
                Collections.<NetworkNode>singletonList(
                        new GeneralArea("R3", "Larger Residential Area", true, 2000)));
        householdPolicy.submitFault(fewerHouseholds);
        householdPolicy.submitFault(moreHouseholds);
        assertTrue(householdPolicy.peekNextFault() == moreHouseholds,
                "greater residential household impact must break economic ties");

        FaultReport criticalFault = new FaultReport(
                network.getLine("L7"),
                Collections.<NetworkNode>singletonList(network.getNode("H1")));
        economicPolicy.submitFault(criticalFault);
        assertTrue(economicPolicy.peekNextFault() == criticalFault,
                "critical infrastructure must outrank economic impact");

        RestorationPolicy fifoPolicy = new RestorationPolicy();
        FaultReport firstInTie =
                new FaultReport(network.getLine("L5"), Collections.emptyList());
        FaultReport secondInTie =
                new FaultReport(network.getLine("L6"), Collections.emptyList());
        fifoPolicy.submitFault(firstInTie);
        fifoPolicy.submitFault(secondInTie);
        assertTrue(fifoPolicy.peekNextFault() == firstInTie,
                "fully equal priority reports should retain FIFO order");
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
        assertEquals(60, report.getEstimatedRepairMinutes(),
                "local depot ETA must include the 45-minute repair and 15-minute travel");
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
        assertEquals("Alpha", firstReport.getAssignedTeam().getName(),
                "first available repair team");
        assertEquals("Bravo", secondReport.getAssignedTeam().getName(),
                "second available repair team");
        assertEquals("Charlie", thirdReport.getAssignedTeam().getName(),
                "third available repair team");
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

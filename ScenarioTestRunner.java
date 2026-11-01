import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ScenarioTestRunner {
    private static final int EXPECTED_NODE_COUNT = 20;
    private static final int EXPECTED_LINE_COUNT = 21;
    private static final int EXPECTED_SUBSTATION_COUNT = 2;
    private static final int EXPECTED_REPAIR_TEAM_COUNT = 3;

    private static final List<Scenario> SCENARIOS = Arrays.asList(
            new Scenario("S00", "Normal network operation", new String[0], null,
                    ids(), ids(), ids()),
            new Scenario("S01", "General-area line fault: L5", new String[] {"L5"}, null,
                    ids("C1"), ids(), ids()),
            new Scenario("S02", "General-area line fault: L6", new String[] {"L6"}, null,
                    ids("C2"), ids(), ids()),
            new Scenario("S03", "Hospital connection fault: L7", new String[] {"L7"}, null,
                    ids("H1"), ids("H1"), ids()),
            new Scenario("S04", "General-area line fault: L11", new String[] {"L11"}, null,
                    ids("C5"), ids(), ids()),
            new Scenario("S05", "Storm tears: L5 + L6", new String[] {"L5", "L6"}, null,
                    ids("C1", "C2"), ids(), ids()),
            new Scenario("S06", "Storm tears: L7 + L8", new String[] {"L7", "L8"}, null,
                    ids("H1", "C3"), ids("H1"), ids()),
            new Scenario("S07", "Storm tears: L9 + L10", new String[] {"L9", "L10"}, null,
                    ids("C4", "E1"), ids("E1"), ids()),
            new Scenario("S08", "Storm tears: L10 + L11", new String[] {"L10", "L11"}, null,
                    ids("E1", "C5"), ids("E1"), ids()),
            new Scenario("S09", "Storm tears: L5 + L7", new String[] {"L5", "L7"}, null,
                    ids("C1", "H1"), ids("H1"), ids()),
            new Scenario("S10", "Peak-load safety trip: L5", new String[] {"L5"}, null,
                    ids("C1"), ids(), ids(), true),
            new Scenario("S11", "Peak-load safety trip: L8", new String[] {"L8"}, null,
                    ids("C3"), ids(), ids(), true),
            new Scenario("S12", "Peak-load safety trips: L9 + L11",
                    new String[] {"L9", "L11"}, null,
                    ids("C4", "C5"), ids(), ids(), true),
            new Scenario("S13", "Substation failure: S1", new String[0], "S1",
                    ids("S1", "F1", "F2", "C1", "C2", "C3", "C6", "C7",
                            "C8", "C9", "H1"), ids("H1"), ids("L12")),
            new Scenario("S14", "Substation failure: S2", new String[0], "S2",
                    ids("S2", "F3", "F4", "C4", "C5", "C10", "C11", "E1", "E2"),
                    ids("E1", "E2"), ids("L12")),
            new Scenario("S15", "Feeder input failures: L1 + L3",
                    new String[] {"L1", "L3"}, null,
                    ids("F1", "F3", "C1", "C2", "C4", "C6", "C7", "C10", "E1", "E2"),
                    ids("E1", "E2"), ids("L13", "L14")));

    public static void main(String[] args) {
        System.out.printf(
                "%-11s | %-43s | %17s | %-18s | %18s | %-6s%n",
                "Scenario ID", "Fault Condition", "Disconnected Nodes",
                "Expected Backup", "Execution Time (ms)", "Status");
        System.out.println(repeat('-', 11) + "-+-" + repeat('-', 43)
                + "-+-" + repeat('-', 17) + "-+-" + repeat('-', 18)
                + "-+-" + repeat('-', 18) + "-+-" + repeat('-', 6));

        int updateEvents = 0;
        int passedScenarios = 0;
        for (Scenario scenario : SCENARIOS) {
            updateEvents += scenario.updateEventCount();
            if (runScenario(scenario)) {
                passedScenarios++;
            }
        }
        System.out.printf("Scenarios: %d/%d passed | fault/update events: %d%n",
                passedScenarios, SCENARIOS.size(), updateEvents);
        if (passedScenarios != SCENARIOS.size()) {
            throw new AssertionError("One or more scenario expectations failed.");
        }
        if (updateEvents < 15) {
            throw new AssertionError("Expected at least 15 fault/update events.");
        }
    }

    private static boolean runScenario(Scenario scenario) {
        long started = System.nanoTime();
        int disconnectedCount = 0;
        String backupPathText = "NONE";
        try {
            DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
            assertEquals(EXPECTED_NODE_COUNT, network.getNodes().size(), "network node count");
            assertEquals(EXPECTED_LINE_COUNT, network.getLines().size(), "network line count");
            assertEquals(EXPECTED_SUBSTATION_COUNT, network.getSubstations().size(),
                    "substation count");

            SensorMonitor monitor = null;
            if (scenario.peakLoadScenario) {
                monitor = new SensorMonitor(20, 30, network);
                for (NetworkLine line : network.getLines()) {
                    monitor.addReading(new SensorReading(line.getId(), 230, 10, 230, 10));
                }
            }
            for (String lineId : scenario.failedLineIds) {
                NetworkLine line = network.getLine(lineId);
                assertTrue(line != null, "scenario references known line " + lineId);
                if (scenario.peakLoadScenario) {
                    monitor.simulatePeakLoadOverloadOnLine(lineId);
                } else {
                    line.failLine();
                }
            }
            if (scenario.peakLoadScenario) {
                Set<String> faultedLineIds = new HashSet<>();
                for (NetworkLine line : monitor.findFaultedLines(network.getLines())) {
                    faultedLineIds.add(line.getId());
                }
                assertEquals(ids(scenario.failedLineIds), faultedLineIds,
                        "peak-load tripped line IDs");
            }
            for (String lineId : scenario.failedLineIds) {
                assertEquals(LineStatus.FAILED, network.getLine(lineId).getStatus(),
                        lineId + " must be failed in the scenario");
            }
            if (scenario.failedSubstationId != null) {
                NetworkNode node = network.getNode(scenario.failedSubstationId);
                assertTrue(node instanceof Substation,
                        "scenario references a known substation");
                node.setPowered(false);
            }

            OutageDetector detector = new OutageDetector();
            long bfsStarted = System.nanoTime();
            List<NetworkNode> disconnectedNodes = detector.findAffectedNodes(network);
            long bfsElapsed = System.nanoTime() - bfsStarted;
            Set<String> actualDisconnectedIds = nodeIds(disconnectedNodes);
            assertEquals(scenario.expectedDisconnectedIds, actualDisconnectedIds,
                    "disconnected node IDs");
            disconnectedCount = disconnectedNodes.size();

            Set<String> actualCriticalIds = new HashSet<>();
            for (NetworkNode node : disconnectedNodes) {
                if (node instanceof CriticalArea) {
                    actualCriticalIds.add(node.getId());
                }
            }
            assertEquals(scenario.expectedCriticalIds, actualCriticalIds,
                    "disconnected critical facility IDs");

            List<String> backupPath = Collections.emptyList();
            long planningElapsed = 0;
            if (scenario.failedLineIds.length > 0 || scenario.failedSubstationId != null) {
                String reportLineId = scenario.failedLineIds.length > 0
                        ? scenario.failedLineIds[0]
                        : scenario.failedSubstationId.equals("S1") ? "L1" : "L3";
                FaultReport report = new FaultReport(
                        network.getLine(reportLineId), disconnectedNodes);
                RestorationPolicy policy = new RestorationPolicy();
                assertEquals(EXPECTED_REPAIR_TEAM_COUNT, policy.getRepairTeams().size(),
                        "repair team count");
                long planningStarted = System.nanoTime();
                backupPath = policy.calculateBestBackupPath(network, detector, report);
                planningElapsed = System.nanoTime() - planningStarted;
            } else {
                assertTrue(detector.findReachableNodes(network).size() == EXPECTED_NODE_COUNT,
                        "normal operation must reach all nodes");
                assertTrue(new RestorationPolicy().getRepairTeams().size()
                                == EXPECTED_REPAIR_TEAM_COUNT,
                        "default repair team count");
            }
            assertEquals(scenario.expectedBackupPath, new HashSet<>(backupPath),
                    "candidate backup link IDs");
            backupPathText = backupPath.isEmpty() ? "NONE" : String.join(" -> ", backupPath);

            double elapsedMilliseconds = (bfsElapsed + planningElapsed) / 1_000_000.0;
            System.out.printf(Locale.ROOT,
                    "%-11s | %-43s | %17d | %-18s | %18.6f | %-6s%n",
                    scenario.id, scenario.faultCondition, disconnectedCount,
                    scenario.expectedBackupPath.isEmpty()
                            ? "NONE" : String.join(" -> ", sorted(scenario.expectedBackupPath)),
                    elapsedMilliseconds, "PASS");
            return true;
        } catch (RuntimeException | AssertionError exception) {
            double elapsedMilliseconds = (System.nanoTime() - started) / 1_000_000.0;
            System.out.printf(Locale.ROOT,
                    "%-11s | %-43s | %17d | %-18s | %18.6f | %-6s%n",
                    scenario.id, scenario.faultCondition, disconnectedCount,
                    backupPathText, elapsedMilliseconds, "FAIL");
            System.err.println(scenario.id + " failed: " + exception.getMessage());
            return false;
        }
    }

    private static Set<String> ids(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    private static Set<String> nodeIds(List<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    private static List<String> sorted(Set<String> values) {
        List<String> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted;
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static String repeat(char value, int count) {
        char[] characters = new char[count];
        Arrays.fill(characters, value);
        return new String(characters);
    }

    private static final class Scenario {
        private final String id;
        private final String faultCondition;
        private final String[] failedLineIds;
        private final String failedSubstationId;
        private final Set<String> expectedDisconnectedIds;
        private final Set<String> expectedCriticalIds;
        private final Set<String> expectedBackupPath;
        private final boolean peakLoadScenario;

        private Scenario(String id, String faultCondition, String[] failedLineIds,
                String failedSubstationId, Set<String> expectedDisconnectedIds,
                Set<String> expectedCriticalIds, Set<String> expectedBackupPath) {
            this(id, faultCondition, failedLineIds, failedSubstationId,
                    expectedDisconnectedIds, expectedCriticalIds, expectedBackupPath, false);
        }

        private Scenario(String id, String faultCondition, String[] failedLineIds,
                String failedSubstationId, Set<String> expectedDisconnectedIds,
                Set<String> expectedCriticalIds, Set<String> expectedBackupPath,
                boolean peakLoadScenario) {
            this.id = id;
            this.faultCondition = faultCondition;
            this.failedLineIds = failedLineIds.clone();
            this.failedSubstationId = failedSubstationId;
            this.expectedDisconnectedIds = new HashSet<>(expectedDisconnectedIds);
            this.expectedCriticalIds = new HashSet<>(expectedCriticalIds);
            this.expectedBackupPath = new HashSet<>(expectedBackupPath);
            this.peakLoadScenario = peakLoadScenario;
        }

        private int updateEventCount() {
            return failedLineIds.length + (failedSubstationId == null ? 0 : 1);
        }
    }
}

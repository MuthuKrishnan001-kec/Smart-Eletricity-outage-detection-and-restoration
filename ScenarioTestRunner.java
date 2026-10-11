import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class ScenarioTestRunner {
    private static final int EXPECTED_NODE_COUNT = 20;

    private static final List<Scenario> SCENARIOS = Arrays.asList(
            new Scenario("S01", "General-area line fault: L5", new String[] {"L5"}, null),
            new Scenario("S02", "General-area line fault: L6", new String[] {"L6"}, null),
            new Scenario("S03", "Hospital connection fault: L7", new String[] {"L7"}, null),
            new Scenario("S04", "General-area line fault: L11", new String[] {"L11"}, null),
            new Scenario("S05", "Storm tears: L5 + L6", new String[] {"L5", "L6"}, null),
            new Scenario("S06", "Storm tears: L7 + L8", new String[] {"L7", "L8"}, null),
            new Scenario("S07", "Storm tears: L9 + L10", new String[] {"L9", "L10"}, null),
            new Scenario("S08", "Storm tears: L10 + L11", new String[] {"L10", "L11"}, null),
            new Scenario("S09", "Storm tears: L5 + L7", new String[] {"L5", "L7"}, null),
            new Scenario("S10", "Peak-load safety trip: L5",
                    new String[] {"L5"}, null, true),
            new Scenario("S11", "Peak-load safety trip: L8",
                    new String[] {"L8"}, null, true),
            new Scenario("S12", "Peak-load safety trips: L9 + L11",
                    new String[] {"L9", "L11"}, null, true),
            new Scenario("S13", "Substation failure: S1", new String[0], "S1"),
            new Scenario("S14", "Substation failure: S2", new String[0], "S2"),
            new Scenario("S15", "Feeder input failures: L1 + L3",
                    new String[] {"L1", "L3"}, null));

    public static void main(String[] args) {
        System.out.printf(
                "%-11s | %-54s | %17s | %-18s | %18s | %-6s%n",
                "Scenario ID", "Fault Condition", "Disconnected Nodes",
                "Best Backup Path", "Execution Time (ms)", "Status");
        System.out.println(repeat('-', 11) + "-+-" + repeat('-', 54)
                + "-+-" + repeat('-', 17) + "-+-" + repeat('-', 18)
                + "-+-" + repeat('-', 18) + "-+-" + repeat('-', 6));

        for (Scenario scenario : SCENARIOS) {
            runScenario(scenario);
        }
    }

    private static void runScenario(Scenario scenario) {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        if (network.getNodes().size() != EXPECTED_NODE_COUNT) {
            throw new IllegalStateException("Expected a 20-node network, found "
                    + network.getNodes().size() + ".");
        }

        NetworkLine reportLine = null;
        boolean peakLoadScenario = scenario.peakLoadScenario;
        SensorMonitor peakLoadMonitor = null;
        if (peakLoadScenario) {
            peakLoadMonitor = new SensorMonitor(20, 30, network);
            for (NetworkLine line : network.getLines()) {
                peakLoadMonitor.addReading(
                        new SensorReading(line.getId(), 230, 10, 230, 10));
            }
        }
        for (String lineId : scenario.failedLineIds) {
            NetworkLine line = network.getLine(lineId);
            if (line == null) {
                throw new IllegalStateException("Unknown scenario line: " + lineId);
            }
            if (peakLoadScenario) {
                peakLoadMonitor.simulatePeakLoadOverloadOnLine(lineId);
            } else {
                line.failLine();
            }
            if (reportLine == null) {
                reportLine = line;
            }
        }
        if (peakLoadScenario
                && peakLoadMonitor.findFaultedLines(network.getLines()).size()
                        != scenario.failedLineIds.length) {
            throw new IllegalStateException(
                    "A configured peak-load safety trip did not fail its line.");
        }
        if (scenario.failedSubstationId != null) {
            NetworkNode node = network.getNode(scenario.failedSubstationId);
            if (!(node instanceof Substation)) {
                throw new IllegalStateException(
                        "Unknown scenario substation: " + scenario.failedSubstationId);
            }
            node.setPowered(false);
            if (reportLine == null) {
                reportLine = network.getLine(
                        scenario.failedSubstationId.equals("S1") ? "L1" : "L3");
            }
        }
        if (reportLine == null) {
            throw new IllegalStateException(
                    "Scenario must contain a line or substation failure.");
        }

        OutageDetector detector = new OutageDetector();
        long bfsStart = System.nanoTime();
        List<NetworkNode> disconnectedNodes = detector.findAffectedNodes(network);
        long bfsElapsed = System.nanoTime() - bfsStart;

        FaultReport report = new FaultReport(reportLine, disconnectedNodes);
        RestorationPolicy policy = new RestorationPolicy();
        long restorationStart = System.nanoTime();
        List<String> backupPath =
                policy.calculateBestBackupPath(network, detector, report);
        long restorationElapsed = System.nanoTime() - restorationStart;

        double elapsedMilliseconds = (bfsElapsed + restorationElapsed) / 1_000_000.0;
        boolean passed = !disconnectedNodes.isEmpty() && backupPath != null;
        String path = backupPath.isEmpty() ? "NONE" : String.join(" -> ", backupPath);
        System.out.printf(
                Locale.ROOT,
                "%-11s | %-54s | %17d | %-18s | %18.6f | %-6s%n",
                scenario.id,
                scenario.faultCondition,
                disconnectedNodes.size(),
                path,
                elapsedMilliseconds,
                passed ? "PASS" : "FAIL");
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
        private final boolean peakLoadScenario;

        private Scenario(
                String id,
                String faultCondition,
                String[] failedLineIds,
                String failedSubstationId) {
            this(id, faultCondition, failedLineIds, failedSubstationId, false);
        }

        private Scenario(
                String id,
                String faultCondition,
                String[] failedLineIds,
                String failedSubstationId,
                boolean peakLoadScenario) {
            this.id = id;
            this.faultCondition = faultCondition;
            this.failedLineIds = failedLineIds.clone();
            this.failedSubstationId = failedSubstationId;
            this.peakLoadScenario = peakLoadScenario;
        }
    }
}

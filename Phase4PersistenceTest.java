import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class Phase4PersistenceTest {
    public static void main(String[] args) throws IOException {
        Path historyFile = Files.createTempFile("outage-history-test-", ".csv");
        try {
            HistoryManager historyManager = new HistoryManager(historyFile);
            String zoneWithCsvCharacters = "North, \"East\"\nSouth";
            historyManager.logEvent(
                    "test-time", "CSV_TEST", "L0",
                    zoneWithCsvCharacters, "Test Crew", "OK");
            List<String[]> events = historyManager.loadHistory();
            assertEquals(1, events.size(), "CSV row count");
            assertEquals(zoneWithCsvCharacters, events.get(0)[3], "CSV escaping");

            DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
            SensorMonitor monitor = new SensorMonitor(20, 30, network, historyManager);
            monitor.addReading(new SensorReading("L1", 230, 10, 180, 10));
            OutageDetector detector = new OutageDetector();
            OutageDiagnostics diagnostics = detector.detectFaults(network, monitor);
            NetworkLine failedLine = network.getLine("L1");
            if (!diagnostics.getFaultedLines().contains(failedLine)) {
                throw new AssertionError("Expected sensor fault detection to fail L1.");
            }
            failedLine.setRepairTimeMinutes(35);

            FaultReport report = new FaultReport(
                    failedLine, diagnostics.getAffectedNodes());
            RestorationPolicy policy =
                    RestorationPolicy.withHistoryManager(historyManager);
            policy.submitFault(report, network);
            if (!policy.restoreNextFault(network, detector)) {
                throw new AssertionError("Expected L1 restoration to complete.");
            }

            events = historyManager.loadHistory();
            assertEquals(6, events.size(), "persisted event count");
            String[] repairEvent = null;
            boolean faultDetected = false;
            boolean backupProposed = false;
            for (String[] event : events) {
                faultDetected |= "FAULT_DETECTED".equals(event[1]);
                backupProposed |= "BACKUP_LINK_PROPOSED".equals(event[1]);
                if ("REPAIR_COMPLETED".equals(event[1])) {
                    repairEvent = event;
                }
            }
            assertEquals(true, faultDetected, "fault event type");
            assertEquals(true, backupProposed, "backup proposal event type");
            assertEquals("REPAIR_COMPLETED", repairEvent[1], "repair event type");
            assertEquals(LineStatus.STANDBY, network.getLine("L14").getStatus(),
                    "standby backup must remain inactive unless an operator approves it");
            if (!repairEvent[5].contains("TRAVEL_TIME=15m")
                    || !repairEvent[5].contains("REPAIR_TIME=35m")
                    || !repairEvent[5].contains("ESTIMATED_RESTORATION=50m")) {
                throw new AssertionError(
                        "Repair, travel, and total ETA should be persisted in repair history.");
            }

            String header = new String(
                    Files.readAllBytes(historyFile), StandardCharsets.UTF_8).split("\\R", 2)[0];
            assertEquals(String.join(",", HistoryManager.getHeaders()), header, "CSV header");
            System.out.println("Phase 4 persistence checks passed.");
        } finally {
            Files.deleteIfExists(historyFile);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected " + expected
                    + " but got " + actual + ".");
        }
    }
}

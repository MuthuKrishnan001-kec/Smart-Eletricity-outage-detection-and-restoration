import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ManualNodeVoltageFaultTest {
    public static void main(String[] args) throws IOException {
        for (NetworkNode editedNode
                : DistributionNetwork.createDemonstrationNetwork().getNodes()) {
            DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
            Path historyFile = Files.createTempFile(
                    "manual-node-voltage-history-", ".csv");
            try {
                HistoryManager historyManager = new HistoryManager(historyFile);
                SensorMonitor sensorMonitor =
                        new SensorMonitor(20, 30, network, historyManager);
                for (NetworkLine line : network.getLines()) {
                    sensorMonitor.addReading(
                            new SensorReading(line.getId(), 230, 10, 230, 10));
                }

                sensorMonitor.updateIncidentLineVoltage(editedNode.getId(), 130);
                OutageDetector detector = new OutageDetector();
                OutageDiagnostics diagnostics =
                        detector.detectFaults(network, sensorMonitor);
                RestorationPolicy policy =
                        RestorationPolicy.withHistoryManager(historyManager);
                int expectedFaults = 0;
                for (NetworkLine incidentLine : network.getIncidentLines(editedNode.getId())) {
                    if (incidentLine.isBackupLink()) {
                        continue;
                    }
                    expectedFaults++;
                    if (!diagnostics.getFaultedLines().contains(incidentLine)) {
                        throw new AssertionError("Low voltage at " + editedNode.getId()
                                + " should detect incident line " + incidentLine.getId());
                    }
                    policy.submitFault(
                            new FaultReport(incidentLine, diagnostics.getAffectedNodes()),
                            network);
                }
                if (expectedFaults == 0) {
                    throw new AssertionError(
                            "Expected a primary incident line for " + editedNode.getId());
                }
                if (policy.getPendingFaultCount() != expectedFaults) {
                    throw new AssertionError("Expected " + expectedFaults
                            + " queued fault(s) for " + editedNode.getId());
                }

                int persistedFaults = 0;
                for (String[] event : historyManager.loadHistory()) {
                    if ("FAULT_DETECTED".equals(event[1])) {
                        persistedFaults++;
                    }
                }
                if (persistedFaults != expectedFaults) {
                    throw new AssertionError("Expected " + expectedFaults
                            + " persisted fault event(s) for " + editedNode.getId()
                            + " but found " + persistedFaults);
                }
            } finally {
                Files.deleteIfExists(historyFile);
            }
        }
        verifyFaultQueueSurvivesHistoryWriteFailure();
        System.out.println("Manual node voltage detection, persistence, and history-lock "
                + "handling passed for all nodes.");
    }

    private static void verifyFaultQueueSurvivesHistoryWriteFailure() throws IOException {
        DistributionNetwork network = DistributionNetwork.createDemonstrationNetwork();
        Path historyFile = Files.createTempFile(
                "locked-outage-history-", ".csv");
        try {
            HistoryManager failingHistoryManager = new HistoryManager(historyFile) {
                @Override
                public synchronized void logEvent(
                        String timestamp,
                        String eventType,
                        String lineId,
                        String affectedZones,
                        String assignedTeam,
                        String status) throws IOException {
                    throw new IOException("Simulated history file lock.");
                }
            };
            SensorMonitor sensorMonitor =
                    new SensorMonitor(20, 30, network, failingHistoryManager);
            NetworkLine failedLine = network.getLines().stream()
                    .filter(line -> !line.isBackupLink())
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Expected a primary line."));
            failedLine.failLine();

            OutageDiagnostics diagnostics =
                    new OutageDetector().detectFaults(network, sensorMonitor);
            if (!diagnostics.getFaultedLines().contains(failedLine)) {
                throw new AssertionError("History failure prevented fault detection.");
            }
            List<UncheckedIOException> failures =
                    sensorMonitor.drainHistoryWriteFailures();
            if (failures.size() != 1
                    || !failures.get(0).getCause().getMessage()
                            .contains("Simulated history file lock")) {
                throw new AssertionError(
                        "Expected the history persistence failure to remain observable.");
            }

            RestorationPolicy policy = new RestorationPolicy();
            FaultReport report = new FaultReport(failedLine, diagnostics.getAffectedNodes());
            policy.submitFault(report, network);
            if (policy.getPendingFaultCount() != 1 || report.getAssignedTeam() == null
                    || report.getAssignedTeam().isAvailable()) {
                throw new AssertionError(
                        "History failure prevented fault queueing or team assignment.");
            }
        } finally {
            Files.deleteIfExists(historyFile);
        }
    }
}

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

public class SensorMonitor {
    private final Map<String, SensorReading> readings = new HashMap<>();
    private final Set<String> safetyTripLines = new LinkedHashSet<>();
    private final double voltageDropThresholdPercent;
    private final double currentDeviationThresholdPercent;
    private final Random random = new Random();
    private final DistributionNetwork network;
    private final HistoryManager historyManager;
    private final Set<String> loggedFaultLineIds = new HashSet<>();
    private final List<UncheckedIOException> historyWriteFailures = new ArrayList<>();

    public SensorMonitor(double voltageDropThresholdPercent,
                         double currentDeviationThresholdPercent) {
        this(voltageDropThresholdPercent, currentDeviationThresholdPercent, null);
    }

    public SensorMonitor(
            double voltageDropThresholdPercent,
            double currentDeviationThresholdPercent,
            DistributionNetwork network) {
        this(voltageDropThresholdPercent, currentDeviationThresholdPercent, network, null);
    }

    public SensorMonitor(
            double voltageDropThresholdPercent,
            double currentDeviationThresholdPercent,
            DistributionNetwork network,
            HistoryManager historyManager) {
        if (voltageDropThresholdPercent < 0 || voltageDropThresholdPercent > 100
                || currentDeviationThresholdPercent < 0) {
            throw new IllegalArgumentException("Invalid sensor thresholds.");
        }
        this.voltageDropThresholdPercent = voltageDropThresholdPercent;
        this.currentDeviationThresholdPercent = currentDeviationThresholdPercent;
        this.network = network;
        this.historyManager = historyManager;
    }

    public void addReading(SensorReading reading) {
        Objects.requireNonNull(reading, "reading");
        readings.put(reading.getLineId(), reading);
    }

    public SensorReading getReading(String lineId) {
        return readings.get(lineId);
    }

    public void updateIncidentLineVoltage(String nodeId, double observedVoltage) {
        if (network == null) {
            throw new IllegalStateException(
                    "Node voltage updates require a monitor attached to a network.");
        }
        if (nodeId == null || network.getNode(nodeId) == null) {
            throw new IllegalArgumentException("Unknown node: " + nodeId);
        }
        if (!Double.isFinite(observedVoltage) || observedVoltage < 0
                || observedVoltage > 1000) {
            throw new IllegalArgumentException(
                    "Voltage must be between 0 and 1000 volts.");
        }

        for (NetworkLine line : network.getIncidentLines(nodeId)) {
            if (line.isBackupLink()) {
                continue;
            }
            SensorReading reading = readings.get(line.getId());
            if (reading != null) {
                addReading(new SensorReading(
                        line.getId(),
                        reading.getNormalVoltage(),
                        reading.getNormalCurrent(),
                        observedVoltage,
                        reading.getObservedCurrent()));
            }
        }
    }

    public List<NetworkLine> simulateCityEvent(String eventType) {
        if (network == null) {
            throw new IllegalStateException(
                    "City event simulation requires a monitor attached to a network.");
        }
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null.");
        }

        switch (eventType) {
            case "Storm":
                return simulateStorm();
            case "Peak Load Overload":
                return simulatePeakLoadOverload();
            case "Random Aging Fault":
                return simulateRandomAgingFault();
            default:
                throw new IllegalArgumentException("Unsupported city event: " + eventType);
        }
    }

    private List<NetworkLine> simulateStorm() {
        List<NetworkLine> candidates = new ArrayList<>();
        for (NetworkLine line : network.getLines()) {
            SensorReading reading = readings.get(line.getId());
            if (isOperationalPrimaryLine(line, reading)
                    && hasResidentialEndpoint(line)) {
                candidates.add(line);
            }
        }
        if (candidates.size() < 2) {
            throw new IllegalStateException(
                    "A storm needs at least two working residential lines.");
        }

        int eventLineCount = Math.min(candidates.size(), 2 + random.nextInt(2));
        List<NetworkLine> affectedLines = new ArrayList<>();
        for (int i = 0; i < eventLineCount; i++) {
            int selectedIndex = random.nextInt(candidates.size());
            NetworkLine line = candidates.remove(selectedIndex);
            setObservedValues(line, 0, readings.get(line.getId()).getObservedCurrent());
            affectedLines.add(line);
        }
        return affectedLines;
    }

    private List<NetworkLine> simulatePeakLoadOverload() {
        List<NetworkLine> candidates = new ArrayList<>();
        for (NetworkLine line : network.getLines()) {
            SensorReading reading = readings.get(line.getId());
            if (isOperationalPrimaryLine(line, reading)
                    && hasPeakLoadEndpoint(line)) {
                candidates.add(line);
            }
        }
        NetworkLine line = chooseEventLine(candidates, "consumer-area");
        return tripPeakLoadLine(line);
    }

    public List<NetworkLine> simulatePeakLoadOverloadOnLine(String lineId) {
        if (network == null) {
            throw new IllegalStateException(
                    "Peak-load simulation requires a monitor attached to a network.");
        }
        if (lineId == null) {
            throw new IllegalArgumentException("lineId must not be null.");
        }
        NetworkLine line = network.getLine(lineId);
        if (line == null) {
            throw new IllegalArgumentException("Unknown line: " + lineId);
        }
        if (!isOperationalPrimaryLine(line, readings.get(lineId))
                || !hasConsumerEndpoint(line)) {
            throw new IllegalStateException(
                    "Peak-load trip requires a working, instrumented consumer line.");
        }
        return tripPeakLoadLine(line);
    }

    private List<NetworkLine> tripPeakLoadLine(NetworkLine line) {
        SensorReading reading = readings.get(line.getId());
        setObservedValues(line, reading.getObservedVoltage(),
                reading.getNormalCurrent() * 1.35);
        safetyTripLines.add(line.getId());
        return java.util.Collections.singletonList(line);
    }

    private List<NetworkLine> simulateRandomAgingFault() {
        List<NetworkLine> candidates = new ArrayList<>();
        for (NetworkLine line : network.getLines()) {
            SensorReading reading = readings.get(line.getId());
            if (isOperationalPrimaryLine(line, reading)) {
                candidates.add(line);
            }
        }
        NetworkLine line = chooseEventLine(candidates, "working primary");
        SensorReading reading = readings.get(line.getId());
        setObservedValues(line, 170, reading.getObservedCurrent());
        return java.util.Collections.singletonList(line);
    }

    private NetworkLine chooseEventLine(List<NetworkLine> candidates, String lineType) {
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "There are no working " + lineType + " lines available for this event.");
        }
        return candidates.get(random.nextInt(candidates.size()));
    }

    private boolean isOperationalPrimaryLine(NetworkLine line, SensorReading reading) {
        return line.isWorking() && !line.isBackupLink() && reading != null;
    }

    private boolean hasResidentialEndpoint(NetworkLine line) {
        return hasConsumerZoneEndpoint(line, ConsumerZone.RESIDENTIAL)
                || hasGeneralAreaEndpoint(line);
    }

    private boolean hasPeakLoadEndpoint(NetworkLine line) {
        NetworkNode from = network.getNode(line.getFromNodeId());
        NetworkNode to = network.getNode(line.getToNodeId());
        return isEconomicZone(from) || isEconomicZone(to)
                || from instanceof GeneralArea || to instanceof GeneralArea;
    }

    private boolean hasConsumerEndpoint(NetworkLine line) {
        return network.getNode(line.getFromNodeId()) instanceof ConsumerArea
                || network.getNode(line.getToNodeId()) instanceof ConsumerArea;
    }

    private boolean hasGeneralAreaEndpoint(NetworkLine line) {
        return network.getNode(line.getFromNodeId()) instanceof GeneralArea
                || network.getNode(line.getToNodeId()) instanceof GeneralArea;
    }

    private boolean isEconomicZone(NetworkNode node) {
        if (!(node instanceof ZonedArea)) {
            return false;
        }
        ConsumerZone zone = ((ZonedArea) node).getZone();
        return zone == ConsumerZone.INDUSTRIAL || zone == ConsumerZone.COMMERCIAL;
    }

    private boolean hasConsumerZoneEndpoint(NetworkLine line, ConsumerZone zone) {
        return isInConsumerZone(network.getNode(line.getFromNodeId()), zone)
                || isInConsumerZone(network.getNode(line.getToNodeId()), zone);
    }

    private boolean isInConsumerZone(NetworkNode node, ConsumerZone zone) {
        return node instanceof ZonedArea && ((ZonedArea) node).getZone() == zone;
    }

    private void setObservedValues(NetworkLine line, double voltage, double current) {
        SensorReading reading = readings.get(line.getId());
        addReading(new SensorReading(
                line.getId(),
                reading.getNormalVoltage(),
                reading.getNormalCurrent(),
                voltage,
                current));
    }

    public void clearAnomaly(String lineId) {
        SensorReading reading = readings.get(lineId);
        if (reading == null) {
            return;
        }
        safetyTripLines.remove(lineId);
        loggedFaultLineIds.remove(lineId);
        addReading(new SensorReading(
                lineId,
                reading.getNormalVoltage(),
                reading.getNormalCurrent(),
                reading.getNormalVoltage(),
                reading.getNormalCurrent()));
    }

    public boolean isAbnormal(SensorReading reading) {
        return reading.isAbnormal(
                voltageDropThresholdPercent, currentDeviationThresholdPercent);
    }

    public boolean isVoltageFault(SensorReading reading) {
        return reading.isVoltageAbnormal(voltageDropThresholdPercent);
    }

    public synchronized List<UncheckedIOException> drainHistoryWriteFailures() {
        List<UncheckedIOException> failures = new ArrayList<>(historyWriteFailures);
        historyWriteFailures.clear();
        return failures;
    }

    public List<String> getAbnormalReasons(SensorReading reading) {
        return reading.getAbnormalReasons(
                voltageDropThresholdPercent, currentDeviationThresholdPercent);
    }

    public List<NetworkLine> findSuspiciousLines(Collection<NetworkLine> lines) {
        List<NetworkLine> suspiciousLines = new ArrayList<>();
        for (NetworkLine line : lines) {
            SensorReading reading = readings.get(line.getId());
            if (line.isWorking() && reading != null && isAbnormal(reading)) {
                suspiciousLines.add(line);
            }
        }
        return suspiciousLines;
    }

    public Set<NetworkLine> findFaultedLines(Collection<NetworkLine> lines) {
        Set<NetworkLine> faultedLines = new LinkedHashSet<>();
        for (NetworkLine line : lines) {
            if (line.getStatus() == LineStatus.FAILED) {
                safetyTripLines.remove(line.getId());
                faultedLines.add(line);
                logFaultDetected(line);
                continue;
            }

            SensorReading reading = readings.get(line.getId());
            if (line.isWorking() && safetyTripLines.remove(line.getId())) {
                line.failLine();
                faultedLines.add(line);
                logFaultDetected(line);
                continue;
            }
            if (line.isWorking() && reading != null && isVoltageFault(reading)) {
                line.failLine();
                faultedLines.add(line);
                logFaultDetected(line);
            }
        }
        return faultedLines;
    }

    private void logFaultDetected(NetworkLine line) {
        if (historyManager == null || loggedFaultLineIds.contains(line.getId())) {
            return;
        }
        try {
            historyManager.logEvent(
                    Instant.now().toString(),
                    "FAULT_DETECTED",
                    line.getId(),
                    line.getFaultZone(),
                    "UNASSIGNED",
                    "DETECTED");
            loggedFaultLineIds.add(line.getId());
        } catch (IOException exception) {
            historyWriteFailures.add(new UncheckedIOException(
                    "Could not persist detected fault " + line.getId(), exception));
        }
    }
}

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SensorMonitor {
    private final Map<String, SensorReading> readings = new HashMap<>();
    private final double voltageDropThresholdPercent;
    private final double currentDeviationThresholdPercent;

    public SensorMonitor(double voltageDropThresholdPercent,
                         double currentDeviationThresholdPercent) {
        if (voltageDropThresholdPercent < 0 || voltageDropThresholdPercent > 100
                || currentDeviationThresholdPercent < 0) {
            throw new IllegalArgumentException("Invalid sensor thresholds.");
        }
        this.voltageDropThresholdPercent = voltageDropThresholdPercent;
        this.currentDeviationThresholdPercent = currentDeviationThresholdPercent;
    }

    public void addReading(SensorReading reading) {
        Objects.requireNonNull(reading, "reading");
        readings.put(reading.getLineId(), reading);
    }

    public SensorReading getReading(String lineId) {
        return readings.get(lineId);
    }

    public boolean isAbnormal(SensorReading reading) {
        return reading.isAbnormal(
                voltageDropThresholdPercent, currentDeviationThresholdPercent);
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
}

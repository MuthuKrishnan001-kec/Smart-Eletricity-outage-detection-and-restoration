import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        readings.put(reading.getLineId(), reading);
    }

    public List<NetworkLine> detectAndFailSuspiciousLines(
            Collection<NetworkLine> lines) {
        List<NetworkLine> suspectedLines = new ArrayList<>();

        for (NetworkLine line : lines) {
            SensorReading reading = readings.get(line.getId());
            if (reading != null && reading.isAbnormal(
                    voltageDropThresholdPercent, currentDeviationThresholdPercent)) {
                line.fail();
                suspectedLines.add(line);
            }
        }

        return suspectedLines;
    }
}

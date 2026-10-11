import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SensorReading {
    private final String lineId;
    private final double normalVoltage;
    private final double normalCurrent;
    private final double observedVoltage;
    private final double observedCurrent;

    public SensorReading(String lineId, double normalVoltage, double normalCurrent,
                         double observedVoltage, double observedCurrent) {
        this.lineId = Objects.requireNonNull(lineId, "lineId");
        if (normalVoltage <= 0 || normalCurrent <= 0
                || observedVoltage < 0 || observedCurrent < 0) {
            throw new IllegalArgumentException("Sensor measurements must be non-negative.");
        }
        this.normalVoltage = normalVoltage;
        this.normalCurrent = normalCurrent;
        this.observedVoltage = observedVoltage;
        this.observedCurrent = observedCurrent;
    }

    public String getLineId() {
        return lineId;
    }

    public double getNormalVoltage() {
        return normalVoltage;
    }

    public double getNormalCurrent() {
        return normalCurrent;
    }

    public double getObservedVoltage() {
        return observedVoltage;
    }

    public double getObservedCurrent() {
        return observedCurrent;
    }

    public boolean isVoltageAbnormal(double dropThresholdPercent) {
        return observedVoltage < normalVoltage * (1 - dropThresholdPercent / 100);
    }

    public boolean isCurrentAbnormal(double deviationThresholdPercent) {
        double difference = Math.abs(observedCurrent - normalCurrent);
        return difference > normalCurrent * deviationThresholdPercent / 100;
    }

    public boolean isAbnormal(double voltageDropThresholdPercent,
                              double currentDeviationThresholdPercent) {
        return isVoltageAbnormal(voltageDropThresholdPercent)
                || isCurrentAbnormal(currentDeviationThresholdPercent);
    }

    public List<String> getAbnormalReasons(double voltageDropThresholdPercent,
                                           double currentDeviationThresholdPercent) {
        List<String> reasons = new ArrayList<>();
        if (isVoltageAbnormal(voltageDropThresholdPercent)) {
            reasons.add("Voltage drop exceeded threshold");
        }
        if (isCurrentAbnormal(currentDeviationThresholdPercent)) {
            reasons.add("Current deviation exceeded threshold");
        }
        return reasons;
    }
}

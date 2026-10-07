public class SensorReading {
    private final String lineId;
    private final double normalVoltage;
    private final double normalCurrent;
    private final double observedVoltage;
    private final double observedCurrent;

    public SensorReading(String lineId, double normalVoltage, double normalCurrent,
                         double observedVoltage, double observedCurrent) {
        this.lineId = lineId;
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

    @Override
    public String toString() {
        return String.format(
                "Line %s: normal %.1f V / %.1f A, observed %.1f V / %.1f A",
                lineId, normalVoltage, normalCurrent, observedVoltage, observedCurrent
        );
    }
}

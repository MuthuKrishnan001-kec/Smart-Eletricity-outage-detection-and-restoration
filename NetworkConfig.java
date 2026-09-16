public class NetworkConfig {
    private int substationCount = 10;
    private int feedersPerSubstation = 2;
    private int transformersPerFeeder = 2;
    private int customersPerTransformer = 5;

    private int hospitalCount = 8;
    private int waterPumpingStationCount = 6;
    private int emergencyCentreCount = 5;

    public int getSubstationCount() { return substationCount; }
    public void setSubstationCount(int substationCount) { this.substationCount = substationCount; }

    public int getFeedersPerSubstation() { return feedersPerSubstation; }
    public void setFeedersPerSubstation(int feedersPerSubstation) { this.feedersPerSubstation = feedersPerSubstation; }

    public int getTransformersPerFeeder() { return transformersPerFeeder; }
    public void setTransformersPerFeeder(int transformersPerFeeder) { this.transformersPerFeeder = transformersPerFeeder; }

    public int getCustomersPerTransformer() { return customersPerTransformer; }
    public void setCustomersPerTransformer(int customersPerTransformer) { this.customersPerTransformer = customersPerTransformer; }

    public int getHospitalCount() { return hospitalCount; }
    public void setHospitalCount(int hospitalCount) { this.hospitalCount = hospitalCount; }

    public int getWaterPumpingStationCount() { return waterPumpingStationCount; }
    public void setWaterPumpingStationCount(int waterPumpingStationCount) { this.waterPumpingStationCount = waterPumpingStationCount; }

    public int getEmergencyCentreCount() { return emergencyCentreCount; }
    public void setEmergencyCentreCount(int emergencyCentreCount) { this.emergencyCentreCount = emergencyCentreCount; }

    public int totalCustomerCount() {
        return substationCount * feedersPerSubstation * transformersPerFeeder * customersPerTransformer;
    }
}

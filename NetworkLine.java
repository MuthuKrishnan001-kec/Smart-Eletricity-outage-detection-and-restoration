import java.util.Objects;

public class NetworkLine {
    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private LineStatus status;
    private final boolean backupLink;
    private final int repairTimeMinutes;
    private final CityDistrict district;

    public NetworkLine(String id, String fromNodeId, String toNodeId,
                       boolean backupLink, int repairTimeMinutes) {
        this(id, fromNodeId, toNodeId, backupLink, repairTimeMinutes,
                CityDistrict.DOWNTOWN);
    }

    public NetworkLine(String id, String fromNodeId, String toNodeId,
                       boolean backupLink, int repairTimeMinutes, CityDistrict district) {
        this.id = requireIdentifier(id, "id");
        this.fromNodeId = requireIdentifier(fromNodeId, "fromNodeId");
        this.toNodeId = requireIdentifier(toNodeId, "toNodeId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("A line must connect two different nodes.");
        }
        if (repairTimeMinutes < 0) {
            throw new IllegalArgumentException("repairTimeMinutes must not be negative.");
        }
        this.backupLink = backupLink;
        this.repairTimeMinutes = repairTimeMinutes;
        if (district == null) {
            throw new IllegalArgumentException("district must not be null.");
        }
        this.district = district;
        this.status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
    }

    private static String requireIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty.");
        }
        return value;
    }

    public String getId() {
        return id;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    public LineStatus getStatus() {
        return status;
    }

    public boolean isBackupLink() {
        return backupLink;
    }

    public int getRepairTimeMinutes() {
        return repairTimeMinutes;
    }

    public CityDistrict getDistrict() {
        return district;
    }

    public String getFaultZone() {
        return district.getDisplayName();
    }

    public void failLine() {
        status = LineStatus.FAILED;
    }

    public void fail() {
        failLine();
    }

    public void restoreLine() {
        status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
    }

    public void repair() {
        restoreLine();
    }

    public void activateBackup() {
        if (backupLink && status != LineStatus.FAILED) {
            status = LineStatus.WORKING;
        }
    }

    public void deactivateBackup() {
        if (backupLink && status != LineStatus.FAILED) {
            status = LineStatus.STANDBY;
        }
    }

    public boolean isWorking() {
        return status == LineStatus.WORKING;
    }
}

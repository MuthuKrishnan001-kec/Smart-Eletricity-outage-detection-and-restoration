public class NetworkLine {
    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private LineStatus status;
    private final boolean backupLink;
    private final int repairTimeMinutes;

    public NetworkLine(String id, String fromNodeId, String toNodeId,
                       boolean backupLink, int repairTimeMinutes) {
        this.id = id;
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.backupLink = backupLink;
        this.repairTimeMinutes = repairTimeMinutes;
        this.status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
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

    public void fail() {
        status = LineStatus.FAILED;
    }

    public void repair() {
        status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
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

import java.util.Objects;

public class NetworkLine {
    private final String id;
    private final String fromNodeId;
    private final String toNodeId;
    private LineStatus status;
    private final boolean backupLink;
    private BackupApprovalStatus backupApprovalStatus;
    private int repairTimeMinutes;
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
        if (repairTimeMinutes <= 0) {
            throw new IllegalArgumentException("repairTimeMinutes must be positive.");
        }
        this.backupLink = backupLink;
        this.repairTimeMinutes = repairTimeMinutes;
        if (district == null) {
            throw new IllegalArgumentException("district must not be null.");
        }
        this.district = district;
        this.status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
        this.backupApprovalStatus = backupLink
                ? BackupApprovalStatus.STANDBY : BackupApprovalStatus.NOT_APPLICABLE;
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

    public BackupApprovalStatus getBackupApprovalStatus() {
        return backupApprovalStatus;
    }

    public int getRepairTimeMinutes() {
        return repairTimeMinutes;
    }

    public void setRepairTimeMinutes(int repairTimeMinutes) {
        if (repairTimeMinutes <= 0) {
            throw new IllegalArgumentException("repairTimeMinutes must be positive.");
        }
        this.repairTimeMinutes = repairTimeMinutes;
    }

    public CityDistrict getDistrict() {
        return district;
    }

    public String getFaultZone() {
        return district.getDisplayName();
    }

    public void failLine() {
        status = LineStatus.FAILED;
        if (backupLink) {
            backupApprovalStatus = BackupApprovalStatus.FAILED;
        }
    }

    public void failLine(int repairTimeMinutes) {
        setRepairTimeMinutes(repairTimeMinutes);
        failLine();
    }

    public void fail() {
        failLine();
    }

    public void restoreLine() {
        status = backupLink ? LineStatus.STANDBY : LineStatus.WORKING;
        if (backupLink) {
            backupApprovalStatus = BackupApprovalStatus.STANDBY;
        }
    }

    public void repair() {
        restoreLine();
    }

    public void activateBackup() {
        if (!backupLink) {
            throw new IllegalStateException("Only predefined backup links can be activated.");
        }
        if (status == LineStatus.FAILED
                || backupApprovalStatus == BackupApprovalStatus.FAILED) {
            throw new IllegalStateException("A failed backup link cannot be activated.");
        }
        if (backupApprovalStatus == BackupApprovalStatus.ACTIVE) {
            throw new IllegalStateException("Backup link " + id + " is already active.");
        }
        if (backupApprovalStatus != BackupApprovalStatus.APPROVED
                || status != LineStatus.STANDBY) {
            throw new IllegalStateException("Backup link " + id + " must be approved before activation.");
        }
        status = LineStatus.WORKING;
        backupApprovalStatus = BackupApprovalStatus.ACTIVE;
    }

    public void deactivateBackup() {
        if (backupLink && status != LineStatus.FAILED) {
            status = LineStatus.STANDBY;
            backupApprovalStatus = BackupApprovalStatus.STANDBY;
        }
    }

    void proposeBackup() {
        requireAvailableStandbyBackup();
        backupApprovalStatus = BackupApprovalStatus.PROPOSED;
    }

    void approveBackup() {
        if (!backupLink || status == LineStatus.FAILED
                || backupApprovalStatus == BackupApprovalStatus.FAILED) {
            throw new IllegalStateException("A failed or non-backup line cannot be approved.");
        }
        if (backupApprovalStatus != BackupApprovalStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed backup link can be approved.");
        }
        backupApprovalStatus = BackupApprovalStatus.APPROVED;
    }

    void rejectBackup() {
        if (!backupLink || status == LineStatus.FAILED
                || backupApprovalStatus == BackupApprovalStatus.FAILED) {
            throw new IllegalStateException("A failed or non-backup line cannot be rejected.");
        }
        if (backupApprovalStatus != BackupApprovalStatus.PROPOSED
                && backupApprovalStatus != BackupApprovalStatus.APPROVED) {
            throw new IllegalStateException("Only a proposed backup link can be rejected.");
        }
        status = LineStatus.STANDBY;
        backupApprovalStatus = BackupApprovalStatus.REJECTED;
    }

    void clearBackupApproval() {
        if (backupLink && status != LineStatus.FAILED
                && backupApprovalStatus != BackupApprovalStatus.ACTIVE) {
            status = LineStatus.STANDBY;
            backupApprovalStatus = BackupApprovalStatus.STANDBY;
        }
    }

    private void requireAvailableStandbyBackup() {
        if (!backupLink || status != LineStatus.STANDBY
                || (backupApprovalStatus != BackupApprovalStatus.STANDBY
                && backupApprovalStatus != BackupApprovalStatus.REJECTED)) {
            throw new IllegalStateException("Only available predefined standby backup links can be proposed.");
        }
    }

    public boolean isWorking() {
        return status == LineStatus.WORKING;
    }
}

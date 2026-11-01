import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BackupLinkProposal {
    private final NetworkLine backupLine;
    private final String failedLineId;
    private final List<String> restorationPath;
    private final List<NetworkNode> affectedConsumers;

    public BackupLinkProposal(
            NetworkLine backupLine,
            String failedLineId,
            List<String> restorationPath,
            List<NetworkNode> affectedConsumers) {
        if (backupLine == null || !backupLine.isBackupLink()) {
            throw new IllegalArgumentException("A proposal must reference a predefined backup line.");
        }
        if (failedLineId == null || restorationPath == null || affectedConsumers == null) {
            throw new IllegalArgumentException("Proposal details must not be null.");
        }
        this.backupLine = backupLine;
        this.failedLineId = failedLineId;
        this.restorationPath = Collections.unmodifiableList(
                new ArrayList<>(restorationPath));
        this.affectedConsumers = Collections.unmodifiableList(
                new ArrayList<>(affectedConsumers));
    }

    public NetworkLine getBackupLine() {
        return backupLine;
    }

    public String getBackupLineId() {
        return backupLine.getId();
    }

    public String getFailedLineId() {
        return failedLineId;
    }

    public List<String> getRestorationPath() {
        return restorationPath;
    }

    public List<NetworkNode> getAffectedConsumers() {
        return affectedConsumers;
    }

    public int getAffectedConsumerCount() {
        int count = 0;
        for (NetworkNode node : affectedConsumers) {
            if (node instanceof ConsumerArea) {
                count += ((ConsumerArea) node).getConsumerCount();
            }
        }
        return count;
    }

    public BackupApprovalStatus getApprovalStatus() {
        return backupLine.getBackupApprovalStatus();
    }
}

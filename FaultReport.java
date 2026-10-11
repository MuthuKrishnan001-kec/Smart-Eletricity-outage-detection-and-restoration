import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public class FaultReport {
    private static final AtomicLong NEXT_REPORT_ORDER = new AtomicLong();

    private final NetworkLine failedLine;
    private List<NetworkNode> affectedNodes;
    private final long reportOrder;
    private int priorityScore;
    private RepairTeam repairTeam;
    private boolean restored;
    private int estimatedRepairTimeMinutes;
    private Runnable priorityChangeListener = () -> { };

    public FaultReport(NetworkLine failedLine, List<NetworkNode> affectedNodes) {
        this.failedLine = Objects.requireNonNull(failedLine, "failedLine");
        this.affectedNodes = new ArrayList<>(
                Objects.requireNonNull(affectedNodes, "affectedNodes"));
        this.reportOrder = NEXT_REPORT_ORDER.getAndIncrement();
        this.priorityScore = calculatePriorityScore(this.affectedNodes);
    }

    private int calculatePriorityScore(List<NetworkNode> nodes) {
        int score = 0;
        for (NetworkNode node : nodes) {
            if (node instanceof ConsumerArea) {
                score += ((ConsumerArea) node).getPriorityWeight();
            }
        }
        return score;
    }

    public NetworkLine getFailedLine() {
        return failedLine;
    }

    public List<NetworkNode> getAffectedNodes() {
        return new ArrayList<>(affectedNodes);
    }

    public void setAffectedNodes(List<NetworkNode> affectedNodes) {
        this.affectedNodes = new ArrayList<>(
                Objects.requireNonNull(affectedNodes, "affectedNodes"));
        priorityScore = calculatePriorityScore(this.affectedNodes);
        priorityChangeListener.run();
    }

    public int getCriticalFacilityCount() {
        int count = 0;
        for (NetworkNode node : affectedNodes) {
            if (node instanceof Hospital || node instanceof EmergencyCentre) {
                count++;
            }
        }
        return count;
    }

    public int getAffectedConsumerCount() {
        int count = 0;
        for (NetworkNode node : affectedNodes) {
            if (node instanceof ConsumerArea) {
                count += ((ConsumerArea) node).getConsumerCount();
            }
        }
        return count;
    }

    public long getReportOrder() {
        return reportOrder;
    }

    public int getPriorityScore() {
        return priorityScore;
    }

    public RepairTeam getRepairTeam() {
        return repairTeam;
    }

    public void assignRepairTeam(RepairTeam repairTeam) {
        this.repairTeam = repairTeam;
    }

    public int getEstimatedRepairTimeMinutes() {
        return estimatedRepairTimeMinutes;
    }

    public void setEstimatedRepairTimeMinutes(int estimatedRepairTimeMinutes) {
        if (estimatedRepairTimeMinutes < 0) {
            throw new IllegalArgumentException(
                    "estimatedRepairTimeMinutes must not be negative.");
        }
        this.estimatedRepairTimeMinutes = estimatedRepairTimeMinutes;
    }

    void setPriorityChangeListener(Runnable priorityChangeListener) {
        this.priorityChangeListener = priorityChangeListener == null
                ? () -> { } : priorityChangeListener;
    }

    public boolean isRestored() {
        return restored;
    }

    public void markRestored() {
        restored = true;
    }
}

import java.util.ArrayList;
import java.util.List;

public class FaultReport {
    private final NetworkLine failedLine;
    private final List<NetworkNode> affectedNodes;
    private final int priorityScore;
    private RepairTeam repairTeam;
    private boolean restored;

    public FaultReport(NetworkLine failedLine, List<NetworkNode> affectedNodes) {
        this.failedLine = failedLine;
        this.affectedNodes = new ArrayList<>(affectedNodes);
        this.priorityScore = calculatePriorityScore(affectedNodes);
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

    public int getPriorityScore() {
        return priorityScore;
    }

    public RepairTeam getRepairTeam() {
        return repairTeam;
    }

    public void assignRepairTeam(RepairTeam repairTeam) {
        this.repairTeam = repairTeam;
    }

    public boolean isRestored() {
        return restored;
    }

    public void markRestored() {
        restored = true;
    }
}

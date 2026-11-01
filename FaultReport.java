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
    private RepairTeam assignedTeam;
    private String teamStatus = "WAITING";
    private boolean lineRepairCompleted;
    private boolean affectedConsumersRestored;
    private boolean networkFullyRestored;
    private int travelTimeMinutes;
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
            if (node instanceof CriticalArea) {
                count++;
            }
        }
        return count;
    }

    public int getIndustrialCommercialEconomicImpact() {
        int impact = 0;
        for (NetworkNode node : affectedNodes) {
            if (node instanceof ConsumerArea) {
                ConsumerArea area = (ConsumerArea) node;
                if (area instanceof ZonedArea
                        && ((ZonedArea) area).getZone() != ConsumerZone.RESIDENTIAL) {
                    impact += Math.multiplyExact(
                            area.getPriorityWeight(), area.getConsumerCount());
                }
            }
        }
        return impact;
    }

    public int getResidentialHouseholdsAffected() {
        int households = 0;
        for (NetworkNode node : affectedNodes) {
            if (!(node instanceof ConsumerArea) || node instanceof CriticalArea) {
                continue;
            }
            ConsumerArea area = (ConsumerArea) node;
            if (!(area instanceof ZonedArea)
                    || ((ZonedArea) area).getZone() == ConsumerZone.RESIDENTIAL) {
                households += area.getConsumerCount();
            }
        }
        return households;
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

    public boolean isLineRepairCompleted() {
        return lineRepairCompleted;
    }

    public boolean areAffectedConsumersRestored() {
        return affectedConsumersRestored;
    }

    public boolean isNetworkFullyRestored() {
        return networkFullyRestored;
    }

    public long getReportOrder() {
        return reportOrder;
    }

    public int getPriorityScore() {
        return priorityScore;
    }

    public RepairTeam getRepairTeam() {
        return assignedTeam;
    }

    public RepairTeam getAssignedTeam() {
        return assignedTeam;
    }

    public void assignRepairTeam(RepairTeam repairTeam) {
        assignedTeam = repairTeam;
        teamStatus = repairTeam == null ? "WAITING" : repairTeam.getStatus().name();
    }

    public String getTeamStatus() {
        return teamStatus;
    }

    public void setTeamStatus(String teamStatus) {
        this.teamStatus = Objects.requireNonNull(teamStatus, "teamStatus");
    }

    public int getRepairTimeMinutes() {
        return failedLine.getRepairTimeMinutes();
    }

    public int getEstimatedRepairTimeMinutes() {
        return getEstimatedRestorationTimeMinutes();
    }

    public int getEstimatedRepairMinutes() {
        return getEstimatedRestorationTimeMinutes();
    }

    public int getTravelTimeMinutes() {
        return travelTimeMinutes;
    }

    public int getEstimatedRestorationTimeMinutes() {
        return Math.addExact(getRepairTimeMinutes(), travelTimeMinutes);
    }

    public void setTravelTimeMinutes(int travelTimeMinutes) {
        if (travelTimeMinutes < 0) {
            throw new IllegalArgumentException("travelTimeMinutes must not be negative.");
        }
        this.travelTimeMinutes = travelTimeMinutes;
    }

    /** Compatibility setter; its value represents the total restoration estimate. */
    public void setEstimatedRepairTimeMinutes(int estimatedRepairTimeMinutes) {
        if (estimatedRepairTimeMinutes < getRepairTimeMinutes()) {
            throw new IllegalArgumentException(
                    "Estimated restoration time cannot be less than repair time.");
        }
        setTravelTimeMinutes(estimatedRepairTimeMinutes - getRepairTimeMinutes());
    }

    public void setEstimatedRepairMinutes(int estimatedRepairMinutes) {
        setEstimatedRepairTimeMinutes(estimatedRepairMinutes);
    }

    void setPriorityChangeListener(Runnable priorityChangeListener) {
        this.priorityChangeListener = priorityChangeListener == null
                ? () -> { } : priorityChangeListener;
    }

    public boolean isRestored() {
        return affectedConsumersRestored;
    }

    public void markRestored() {
        lineRepairCompleted = true;
        affectedConsumersRestored = true;
        networkFullyRestored = true;
    }

    void markLineRepairCompleted() {
        lineRepairCompleted = true;
    }

    void updateRestorationStatus(
            boolean consumersRestored, boolean networkRestored) {
        affectedConsumersRestored = consumersRestored;
        networkFullyRestored = networkRestored;
    }
}

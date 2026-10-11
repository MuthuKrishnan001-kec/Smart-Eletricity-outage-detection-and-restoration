import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

public class RestorationPolicy {
    private static final Comparator<FaultReport> FAULT_PRIORITY = Comparator
            .comparingInt(FaultReport::getCriticalFacilityCount).reversed()
            .thenComparing(Comparator
                    .comparingInt(FaultReport::getAffectedConsumerCount).reversed())
            .thenComparingLong(FaultReport::getReportOrder);

    private final PriorityQueue<FaultReport> faultQueue =
            new PriorityQueue<>(FAULT_PRIORITY);
    private final List<FaultReport> history = new ArrayList<>();
    private final List<RepairTeam> repairTeams = new ArrayList<>();

    public RestorationPolicy() {
        this(defaultRepairTeams());
    }

    public RestorationPolicy(Collection<RepairTeam> repairTeams) {
        if (repairTeams == null) {
            throw new IllegalArgumentException("repairTeams must not be null.");
        }
        for (RepairTeam team : repairTeams) {
            if (team == null) {
                throw new IllegalArgumentException("repairTeams must not contain null.");
            }
            this.repairTeams.add(team);
        }
    }

    private static List<RepairTeam> defaultRepairTeams() {
        List<RepairTeam> teams = new ArrayList<>();
        teams.add(new RepairTeam("RT1", "Alpha Crew", "S1"));
        teams.add(new RepairTeam("RT2", "Bravo Crew", "S2"));
        teams.add(new RepairTeam("RT3", "Charlie Crew", "S1"));
        return teams;
    }

    public void submitFault(FaultReport report) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null.");
        }
        if (history.contains(report)) {
            throw new IllegalArgumentException("Fault report has already been submitted.");
        }
        report.setPriorityChangeListener(() -> {
            if (faultQueue.remove(report)) {
                faultQueue.add(report);
            }
        });
        faultQueue.add(report);
        history.add(report);
    }

    public int getPendingFaultCount() {
        return faultQueue.size();
    }

    public FaultReport peekNextFault() {
        return faultQueue.peek();
    }

    public List<FaultReport> getHistory() {
        return new ArrayList<>(history);
    }

    public List<RepairTeam> getRepairTeams() {
        return new ArrayList<>(repairTeams);
    }

    public boolean isPendingFault(NetworkLine line) {
        for (FaultReport report : faultQueue) {
            if (report.getFailedLine() == line) {
                return true;
            }
        }
        return false;
    }

    public void reset() {
        faultQueue.clear();
        history.clear();
        for (RepairTeam team : repairTeams) {
            team.release();
        }
    }

    public boolean restoreNextFault(DistributionNetwork network, OutageDetector detector) {
        if (network == null || detector == null) {
            throw new IllegalArgumentException("network and detector must not be null.");
        }
        FaultReport report = faultQueue.peek();
        if (report == null) {
            System.out.println("There are no faults waiting for restoration.");
            return false;
        }

        RepairAssignment assignment = findBestAvailableTeam(network, report.getFailedLine());
        if (assignment == null) {
            System.out.println("No repair team is currently available.");
            return false;
        }

        faultQueue.remove(report);
        RepairTeam team = assignment.team;
        team.assign();
        report.assignRepairTeam(team);
        report.setEstimatedRepairTimeMinutes(assignment.estimatedTimeMinutes);
        NetworkLine failedLine = report.getFailedLine();
        System.out.println("Selected fault: " + failedLine.getId()
                + " (critical facilities " + report.getCriticalFacilityCount()
                + ", affected consumers " + report.getAffectedConsumerCount() + ")");
        System.out.println("Assigned repair team: " + team.getId() + " - " + team.getName());
        System.out.println("Estimated repair time: "
                + report.getEstimatedRepairTimeMinutes() + " minute(s)");

        try {
            RestorationPlan plan = chooseBestPlan(network, detector, report);
            System.out.println("Selected backup plan score: " + plan.score);
            if (plan.backupLines.isEmpty()) {
                System.out.println("No approved backup lines are needed for the selected plan.");
            } else {
                for (NetworkLine backupLine : plan.backupLines) {
                    backupLine.activateBackup();
                    System.out.println(backupLine.getId() + " status: "
                            + backupLine.getStatus());
                }
            }

            Set<NetworkNode> reachableWithPlan = detector.findReachableNodes(network);
            boolean planRestoresGrid = reachableWithPlan.size() == network.getNodes().size();
            System.out.println("Backup-plan BFS: " + reachableWithPlan.size() + "/"
                    + network.getNodes().size() + " nodes reachable.");
            if (!planRestoresGrid) {
                System.out.println("The selected backup plan does not restore the full grid; "
                        + "repairing the failed line.");
            }

            failedLine.restoreLine();
            System.out.println(failedLine.getId() + " status after repair: "
                    + failedLine.getStatus());

            List<NetworkNode> remainingAffected = detector.findAffectedNodes(network);
            boolean allNodesRestored = remainingAffected.isEmpty();
            System.out.println("Running final BFS restoration verification...");
            if (allNodesRestored) {
                report.markRestored();
                System.out.println("Restoration verified: all network nodes are reachable.");
            } else {
                System.out.println("Restoration incomplete: " + remainingAffected.size()
                        + " node(s) remain unreachable.");
            }
            return allNodesRestored;
        } finally {
            team.release();
            System.out.println(team.getId() + " is now AVAILABLE.");
        }
    }

    public int estimateRepairTimeMinutes(
            DistributionNetwork network, RepairTeam team, NetworkLine failedLine) {
        if (network == null || team == null || failedLine == null) {
            throw new IllegalArgumentException(
                    "network, team and failedLine must not be null.");
        }
        int travelTime = estimateTravelTimeMinutes(network, team, failedLine);
        return Math.addExact(failedLine.getRepairTimeMinutes(), travelTime);
    }

    private RepairAssignment findBestAvailableTeam(
            DistributionNetwork network, NetworkLine failedLine) {
        RepairAssignment best = null;
        for (RepairTeam team : repairTeams) {
            if (!team.isAvailable()) {
                continue;
            }
            int estimatedTime = estimateRepairTimeMinutes(network, team, failedLine);
            RepairAssignment candidate = new RepairAssignment(team, estimatedTime);
            if (best == null || candidate.estimatedTimeMinutes < best.estimatedTimeMinutes
                    || (candidate.estimatedTimeMinutes == best.estimatedTimeMinutes
                    && team.getId().compareTo(best.team.getId()) < 0)) {
                best = candidate;
            }
        }
        return best;
    }

    private int estimateTravelTimeMinutes(
            DistributionNetwork network, RepairTeam team, NetworkLine failedLine) {
        String depotNodeId = team.getDepotNodeId();
        if (depotNodeId == null || network.getNode(depotNodeId) == null) {
            throw new IllegalStateException("Repair team " + team.getId()
                    + " has no valid depot in the distribution network.");
        }

        Map<String, Long> distances = new HashMap<>();
        PriorityQueue<TravelStep> pending = new PriorityQueue<>(
                Comparator.comparingLong(step -> step.travelTimeMinutes));
        distances.put(depotNodeId, 0L);
        pending.add(new TravelStep(depotNodeId, 0));
        long shortestTravelTime = Long.MAX_VALUE;

        while (!pending.isEmpty()) {
            TravelStep step = pending.remove();
            if (step.travelTimeMinutes != distances.get(step.nodeId)) {
                continue;
            }
            if (step.nodeId.equals(failedLine.getFromNodeId())
                    || step.nodeId.equals(failedLine.getToNodeId())) {
                shortestTravelTime = step.travelTimeMinutes;
                break;
            }
            for (NetworkLine line : network.getIncidentLines(step.nodeId)) {
                String nextNodeId = network.getOtherNodeId(line, step.nodeId);
                long nextTime = step.travelTimeMinutes + line.getRepairTimeMinutes();
                Long knownTime = distances.get(nextNodeId);
                if (knownTime == null || nextTime < knownTime) {
                    distances.put(nextNodeId, nextTime);
                    pending.add(new TravelStep(nextNodeId, nextTime));
                }
            }
        }

        if (shortestTravelTime == Long.MAX_VALUE
                || shortestTravelTime > Integer.MAX_VALUE) {
            throw new IllegalStateException("No travel route from repair team "
                    + team.getId() + " to line " + failedLine.getId() + ".");
        }
        return (int) shortestTravelTime;
    }

    private RestorationPlan chooseBestPlan(
            DistributionNetwork network, OutageDetector detector, FaultReport report) {
        RestorationPlan best = null;
        for (Set<NetworkLine> backupLines : generateBackupPlans(network, detector)) {
            Set<NetworkNode> reachable =
                    detector.findReachableNodes(network, backupLines);
            int criticalRestored = 0;
            int consumersRestored = 0;
            for (NetworkNode node : report.getAffectedNodes()) {
                if (!reachable.contains(node)) {
                    continue;
                }
                if (node instanceof Hospital || node instanceof EmergencyCentre) {
                    criticalRestored++;
                }
                if (node instanceof ConsumerArea) {
                    consumersRestored += ((ConsumerArea) node).getConsumerCount();
                }
            }
            long score = criticalRestored * 50L + consumersRestored * 10L
                    - report.getEstimatedRepairTimeMinutes();
            RestorationPlan candidate = new RestorationPlan(
                    backupLines, criticalRestored, consumersRestored, score);
            if (best == null || candidate.isBetterThan(best)) {
                best = candidate;
            }
        }
        if (best == null) {
            throw new IllegalStateException("No restoration plan candidates were generated.");
        }
        return best;
    }

    private List<Set<NetworkLine>> generateBackupPlans(
            DistributionNetwork network, OutageDetector detector) {
        List<Set<NetworkLine>> plans = new ArrayList<>();
        Deque<Set<NetworkLine>> pendingPlans = new ArrayDeque<>();
        Set<String> knownPlans = new HashSet<>();
        Set<NetworkLine> emptyPlan = new LinkedHashSet<>();
        pendingPlans.add(emptyPlan);
        knownPlans.add(planKey(emptyPlan));

        while (!pendingPlans.isEmpty()) {
            Set<NetworkLine> plan = pendingPlans.remove();
            plans.add(plan);
            Set<NetworkNode> reachable = detector.findReachableNodes(network, plan);
            if (reachable.size() == network.getNodes().size()) {
                continue;
            }

            for (List<NetworkLine> path : findBackupPaths(network, reachable, plan)) {
                Set<NetworkLine> expandedPlan = new LinkedHashSet<>(plan);
                expandedPlan.addAll(path);
                String key = planKey(expandedPlan);
                if (expandedPlan.size() > plan.size() && knownPlans.add(key)) {
                    pendingPlans.add(expandedPlan);
                }
            }
        }
        return plans;
    }

    private List<List<NetworkLine>> findBackupPaths(
            DistributionNetwork network,
            Set<NetworkNode> reachable,
            Set<NetworkLine> alreadyActivated) {
        Set<String> reachableIds = getNodeIds(reachable);
        List<List<NetworkLine>> paths = new ArrayList<>();
        Set<String> knownPaths = new HashSet<>();

        for (NetworkNode startNode : network.getNodes()) {
            if (reachableIds.contains(startNode.getId())) {
                continue;
            }
            Deque<PathStep> stack = new ArrayDeque<>();
            Set<String> initialVisited = new HashSet<>();
            initialVisited.add(startNode.getId());
            stack.push(new PathStep(
                    startNode.getId(), new ArrayList<>(), initialVisited));

            while (!stack.isEmpty()) {
                PathStep step = stack.pop();
                List<NetworkLine> incidentLines = network.getIncidentLines(step.nodeId);
                for (int i = incidentLines.size() - 1; i >= 0; i--) {
                    NetworkLine line = incidentLines.get(i);
                    if (!line.isBackupLink()
                            || line.getStatus() != LineStatus.STANDBY
                            || alreadyActivated.contains(line)) {
                        continue;
                    }
                    String nextNodeId = network.getOtherNodeId(line, step.nodeId);
                    if (step.visitedNodeIds.contains(nextNodeId)) {
                        continue;
                    }
                    List<NetworkLine> nextPath = new ArrayList<>(step.path);
                    nextPath.add(line);
                    if (reachableIds.contains(nextNodeId)) {
                        String pathKey = lineIds(nextPath);
                        if (knownPaths.add(pathKey)) {
                            paths.add(nextPath);
                        }
                        continue;
                    }
                    Set<String> visitedNodeIds = new HashSet<>(step.visitedNodeIds);
                    visitedNodeIds.add(nextNodeId);
                    stack.push(new PathStep(nextNodeId, nextPath, visitedNodeIds));
                }
            }
        }
        return paths;
    }

    private Set<String> getNodeIds(Iterable<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    private String planKey(Collection<NetworkLine> lines) {
        return lineIds(lines);
    }

    private String lineIds(Collection<NetworkLine> lines) {
        Set<String> ids = new TreeSet<>();
        for (NetworkLine line : lines) {
            ids.add(line.getId());
        }
        return String.join(",", ids);
    }

    private static class RepairAssignment {
        private final RepairTeam team;
        private final int estimatedTimeMinutes;

        private RepairAssignment(RepairTeam team, int estimatedTimeMinutes) {
            this.team = team;
            this.estimatedTimeMinutes = estimatedTimeMinutes;
        }
    }

    private static class RestorationPlan {
        private final Set<NetworkLine> backupLines;
        private final int criticalRestored;
        private final int consumersRestored;
        private final long score;

        private RestorationPlan(
                Set<NetworkLine> backupLines,
                int criticalRestored,
                int consumersRestored,
                long score) {
            this.backupLines = new LinkedHashSet<>(backupLines);
            this.criticalRestored = criticalRestored;
            this.consumersRestored = consumersRestored;
            this.score = score;
        }

        private boolean isBetterThan(RestorationPlan other) {
            if (score != other.score) {
                return score > other.score;
            }
            if (criticalRestored != other.criticalRestored) {
                return criticalRestored > other.criticalRestored;
            }
            if (consumersRestored != other.consumersRestored) {
                return consumersRestored > other.consumersRestored;
            }
            return lineIdsForComparison().compareTo(other.lineIdsForComparison()) < 0;
        }

        private String lineIdsForComparison() {
            Set<String> ids = new TreeSet<>();
            for (NetworkLine line : backupLines) {
                ids.add(line.getId());
            }
            return String.join(",", ids);
        }
    }

    private static class PathStep {
        private final String nodeId;
        private final List<NetworkLine> path;
        private final Set<String> visitedNodeIds;

        private PathStep(
                String nodeId, List<NetworkLine> path, Set<String> visitedNodeIds) {
            this.nodeId = nodeId;
            this.path = path;
            this.visitedNodeIds = visitedNodeIds;
        }
    }

    private static class TravelStep {
        private final String nodeId;
        private final long travelTimeMinutes;

        private TravelStep(String nodeId, long travelTimeMinutes) {
            this.nodeId = nodeId;
            this.travelTimeMinutes = travelTimeMinutes;
        }
    }
}

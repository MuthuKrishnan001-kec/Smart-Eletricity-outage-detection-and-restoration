import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;

public class RestorationPolicy {
    private static final Comparator<FaultReport> FAULT_PRIORITY = Comparator
            .comparingInt(FaultReport::getCriticalFacilityCount).reversed()
            .thenComparing(Comparator
                    .comparingInt(
                            FaultReport::getIndustrialCommercialEconomicImpact).reversed())
            .thenComparing(Comparator
                    .comparingInt(FaultReport::getResidentialHouseholdsAffected).reversed())
            .thenComparingLong(FaultReport::getReportOrder);

    private final PriorityQueue<FaultReport> faultQueue =
            new PriorityQueue<>(FAULT_PRIORITY);
    private final List<FaultReport> history = new ArrayList<>();
    private final List<RepairTeam> repairTeams = new ArrayList<>();
    private final HistoryManager historyManager;

    public RestorationPolicy() {
        this(defaultRepairTeams(), null);
    }

    public RestorationPolicy(Collection<RepairTeam> repairTeams) {
        this(repairTeams, null);
    }

    private RestorationPolicy(
            Collection<RepairTeam> repairTeams, HistoryManager historyManager) {
        if (repairTeams == null) {
            throw new IllegalArgumentException("repairTeams must not be null.");
        }
        for (RepairTeam team : repairTeams) {
            if (team == null) {
                throw new IllegalArgumentException("repairTeams must not contain null.");
            }
            this.repairTeams.add(team);
        }
        this.historyManager = historyManager;
    }

    public static RestorationPolicy withHistoryManager(HistoryManager historyManager) {
        if (historyManager == null) {
            throw new IllegalArgumentException("historyManager must not be null.");
        }
        return new RestorationPolicy(defaultRepairTeams(), historyManager);
    }

    private static List<RepairTeam> defaultRepairTeams() {
        List<RepairTeam> teams = new ArrayList<>();
        teams.add(new RepairTeam("RT1", "Alpha"));
        teams.add(new RepairTeam("RT2", "Bravo"));
        teams.add(new RepairTeam("RT3", "Charlie"));
        return teams;
    }

    public void submitFault(FaultReport report) {
        submitFault(report, null);
    }

    public void submitFault(FaultReport report, DistributionNetwork network) {
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
        assignAvailableTeams(network);
    }

    public int getPendingFaultCount() {
        return faultQueue.size();
    }

    public FaultReport peekNextFault() {
        return faultQueue.peek();
    }

    public PriorityQueue<FaultReport> getPendingFaultQueue() {
        return new PriorityQueue<>(faultQueue);
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
        assignAvailableTeams(network);
        FaultReport report = faultQueue.peek();
        if (report != null && report.getAssignedTeam() == null) {
            for (FaultReport pendingReport : faultQueue) {
                if (pendingReport.getAssignedTeam() != null
                        && (report.getAssignedTeam() == null
                                || FAULT_PRIORITY.compare(pendingReport, report) < 0)) {
                    report = pendingReport;
                }
            }
        }
        if (report == null) {
            System.out.println("There are no faults waiting for restoration.");
            return false;
        }

        faultQueue.remove(report);
        RepairTeam team = report.getAssignedTeam();
        if (team == null) {
            RepairAssignment assignment =
                    findBestAvailableTeam(network, report.getFailedLine());
            if (assignment == null) {
                faultQueue.add(report);
                System.out.println("No repair team is currently available.");
                return false;
            }
            team = assignment.team;
            team.assign();
            report.assignRepairTeam(team);
            report.setEstimatedRepairMinutes(assignment.estimatedTimeMinutes);
        }
        NetworkLine failedLine = report.getFailedLine();
        System.out.println("Selected fault: " + failedLine.getId()
                + " (critical facilities " + report.getCriticalFacilityCount()
                + ", affected consumers " + report.getAffectedConsumerCount() + ")");
        System.out.println("Assigned repair team: " + team.getId() + " - " + team.getName());
        System.out.println("Estimated repair time: "
                + report.getEstimatedRepairTimeMinutes() + " minute(s)");

        List<UncheckedIOException> historyFailures = new ArrayList<>();
        boolean allNodesRestored = false;
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
                logRestorationEvent(
                        "BACKUP_PLAN_ACTIVATED",
                        lineIds(plan.backupLines),
                        affectedZones(report),
                        team.getName(),
                        "ACTIVATED",
                        historyFailures);
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
            allNodesRestored = remainingAffected.isEmpty();
            logRestorationEvent(
                    "REPAIR_COMPLETED",
                    failedLine.getId(),
                    affectedZones(report),
                    team.getName(),
                    allNodesRestored ? "RESTORED" : "INCOMPLETE",
                    historyFailures);
            System.out.println("Running final BFS restoration verification...");
            if (allNodesRestored) {
                report.markRestored();
                System.out.println("Restoration verified: all network nodes are reachable.");
            } else {
                System.out.println("Restoration incomplete: " + remainingAffected.size()
                        + " node(s) remain unreachable.");
            }
        } finally {
            team.release();
            report.setTeamStatus(team.getStatus().name());
            System.out.println(team.getId() + " is now AVAILABLE.");
            assignAvailableTeams(network);
        }
        if (!historyFailures.isEmpty()) {
            UncheckedIOException failure = historyFailures.get(0);
            for (int i = 1; i < historyFailures.size(); i++) {
                failure.addSuppressed(historyFailures.get(i));
            }
            throw failure;
        }
        return allNodesRestored;
    }

    public int estimateRepairTimeMinutes(
            DistributionNetwork network, RepairTeam team, NetworkLine failedLine) {
        if (team == null || failedLine == null) {
            throw new IllegalArgumentException("team and failedLine must not be null.");
        }
        int travelTime = team.getHomeDistrict().travelMinutesTo(failedLine.getDistrict());
        return Math.addExact(45, travelTime);
    }

    public List<String> calculateBestBackupPath(
            DistributionNetwork network, OutageDetector detector, FaultReport report) {
        if (network == null || detector == null || report == null) {
            throw new IllegalArgumentException(
                    "network, detector, and report must not be null.");
        }
        RestorationPlan plan = chooseBestPlan(network, detector, report);
        List<String> path = new ArrayList<>();
        for (NetworkLine line : plan.backupLines) {
            path.add(line.getId());
        }
        Collections.sort(path);
        return path;
    }

    private void logRestorationEvent(
            String eventType,
            String lineId,
            String affectedZones,
            String assignedTeam,
            String status,
            List<UncheckedIOException> failures) {
        if (historyManager == null) {
            return;
        }
        try {
            historyManager.logEvent(
                    Instant.now().toString(), eventType, lineId,
                    affectedZones, assignedTeam, status);
        } catch (IOException exception) {
            failures.add(new UncheckedIOException(
                    "Could not persist restoration event " + eventType, exception));
        }
    }

    private String affectedZones(FaultReport report) {
        Set<String> zones = new TreeSet<>();
        for (NetworkNode node : report.getAffectedNodes()) {
            if (node instanceof ZonedArea) {
                zones.add(((ZonedArea) node).getDistrict().getDisplayName());
            }
        }
        if (zones.isEmpty()) {
            zones.add(report.getFailedLine().getFaultZone());
        }
        return String.join(", ", zones);
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

    private void assignAvailableTeams(DistributionNetwork network) {
        List<FaultReport> pendingReports = new ArrayList<>(faultQueue);
        pendingReports.sort(FAULT_PRIORITY);
        for (FaultReport report : pendingReports) {
            if (report.getAssignedTeam() != null) {
                continue;
            }
            RepairAssignment assignment;
            if (network == null) {
                assignment = findBestAvailableTeamWithoutNetwork(report.getFailedLine());
            } else {
                assignment = findBestAvailableTeam(network, report.getFailedLine());
            }
            if (assignment == null) {
                int estimate = estimateWithAnyTeam(network, report.getFailedLine());
                if (estimate > 0) {
                    report.setEstimatedRepairMinutes(estimate);
                }
                continue;
            }
            assignment.team.assign();
            report.assignRepairTeam(assignment.team);
            report.setTeamStatus(assignment.team.getStatus().name());
            report.setEstimatedRepairMinutes(assignment.estimatedTimeMinutes);
        }
    }

    private int estimateWithAnyTeam(DistributionNetwork network, NetworkLine failedLine) {
        int bestEstimate = Integer.MAX_VALUE;
        for (RepairTeam team : repairTeams) {
            bestEstimate = Math.min(bestEstimate,
                    estimateRepairTimeMinutes(network, team, failedLine));
        }
        return bestEstimate == Integer.MAX_VALUE ? 0 : bestEstimate;
    }

    private RepairAssignment findBestAvailableTeamWithoutNetwork(NetworkLine failedLine) {
        RepairAssignment best = null;
        for (RepairTeam team : repairTeams) {
            if (!team.isAvailable()) {
                continue;
            }
            RepairAssignment candidate =
                    new RepairAssignment(team, estimateRepairTimeMinutes(null, team, failedLine));
            if (best == null
                    || candidate.estimatedTimeMinutes < best.estimatedTimeMinutes
                    || (candidate.estimatedTimeMinutes == best.estimatedTimeMinutes
                    && team.getId().compareTo(best.team.getId()) < 0)) {
                best = candidate;
            }
        }
        return best;
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
                if (node instanceof CriticalArea) {
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

}

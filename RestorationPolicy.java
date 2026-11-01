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
import java.util.LinkedHashMap;
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
    private final Map<String, BackupLinkProposal> backupProposals = new LinkedHashMap<>();
    private final HistoryManager historyManager;
    private DistributionNetwork assignmentNetwork;

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

    public synchronized void submitFault(FaultReport report) {
        submitFault(report, null);
    }

    public synchronized void submitFault(
            FaultReport report, DistributionNetwork network) {
        if (report == null) {
            throw new IllegalArgumentException("report must not be null.");
        }
        if (history.contains(report)) {
            throw new IllegalArgumentException("Fault report has already been submitted.");
        }
        if (network != null || assignmentNetwork == null) {
            assignmentNetwork = network;
        }
        report.setPriorityChangeListener(() -> reorderAndReassign(report));
        faultQueue.add(report);
        history.add(report);
        assignAvailableTeams(assignmentNetwork);
        if (network != null && report.getFailedLine().getStatus() == LineStatus.FAILED
                && !report.getFailedLine().isBackupLink()) {
            proposeBackupLinks(network, new OutageDetector(), report);
        }
        if (network != null) {
            refreshRestorationStatuses(network, new OutageDetector());
        }
    }

    private synchronized void reorderAndReassign(FaultReport report) {
        if (faultQueue.remove(report)) {
            faultQueue.add(report);
            assignAvailableTeams(assignmentNetwork);
        }
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

    public synchronized List<FaultReport> getFaultReports() {
        return new ArrayList<>(history);
    }

    public synchronized FaultReport getFaultReport(String lineId) {
        if (lineId == null) {
            return null;
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            FaultReport report = history.get(index);
            if (report.getFailedLine().getId().equals(lineId)) {
                return report;
            }
        }
        return null;
    }

    /** Recalculates every report against the current graph and returns network status. */
    public synchronized boolean refreshRestorationStatuses(
            DistributionNetwork network, OutageDetector detector) {
        if (network == null || detector == null) {
            throw new IllegalArgumentException("network and detector must not be null.");
        }
        detector.findAffectedNodes(network);
        Set<NetworkNode> reachable = detector.findReachableNodes(network);
        return updateRestorationStatuses(network, reachable);
    }

    public synchronized boolean isNetworkFullyRestored(
            DistributionNetwork network, OutageDetector detector) {
        return refreshRestorationStatuses(network, detector);
    }

    private boolean updateRestorationStatuses(
            DistributionNetwork network, Set<NetworkNode> reachable) {
        boolean allNodesReachable = reachable.size() == network.getNodes().size();
        boolean failedLineRemains = false;
        for (NetworkLine line : network.getLines()) {
            if (line.getStatus() == LineStatus.FAILED) {
                failedLineRemains = true;
                break;
            }
        }
        boolean networkRestored = allNodesReachable && !failedLineRemains;

        for (FaultReport report : history) {
            boolean consumersRestored = true;
            for (NetworkNode affectedNode : report.getAffectedNodes()) {
                if (affectedNode instanceof ConsumerArea && !reachable.contains(affectedNode)) {
                    consumersRestored = false;
                    break;
                }
            }
            report.updateRestorationStatus(consumersRestored, networkRestored);
        }
        return networkRestored;
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

    public synchronized void reset() {
        faultQueue.clear();
        history.clear();
        backupProposals.clear();
        assignmentNetwork = null;
        for (RepairTeam team : repairTeams) {
            team.release();
        }
    }

    public synchronized boolean restoreNextFault(
            DistributionNetwork network, OutageDetector detector) {
        if (network == null || detector == null) {
            throw new IllegalArgumentException("network and detector must not be null.");
        }
        assignmentNetwork = network;
        assignAvailableTeams(network);
        FaultReport report = faultQueue.peek();
        if (report == null) {
            System.out.println("There are no faults waiting for restoration.");
            return false;
        }
        RepairTeam team = report.getAssignedTeam();
        if (team == null) {
            System.out.println("No repair team is currently available.");
            return false;
        }
        if (team.isAvailable()) {
            team.assign();
        }
        faultQueue.remove(report);
        NetworkLine failedLine = report.getFailedLine();
        System.out.println("Selected fault: " + failedLine.getId()
                + " (critical facilities " + report.getCriticalFacilityCount()
                + ", affected consumers " + report.getAffectedConsumerCount() + ")");
        System.out.println("Assigned repair team: " + team.getId() + " - " + team.getName());
        System.out.println("Estimated travel time: "
                + report.getTravelTimeMinutes() + " minute(s)");
        System.out.println("Operator-entered repair time: "
                + report.getRepairTimeMinutes() + " minute(s)");
        System.out.println("Total estimated restoration time: "
                + report.getEstimatedRestorationTimeMinutes() + " minute(s)");

        List<UncheckedIOException> historyFailures = new ArrayList<>();
        boolean networkRestored = false;
        try {
            System.out.println("No backup link will be activated automatically during repair.");

            failedLine.restoreLine();
            System.out.println(failedLine.getId() + " status after repair: "
                    + failedLine.getStatus());
            expireBackupProposals(report);

            report.markLineRepairCompleted();
            List<NetworkNode> remainingAffected = detector.findAffectedNodes(network);
            Set<NetworkNode> reachable = detector.findReachableNodes(network);
            networkRestored = updateRestorationStatuses(network, reachable);
            int remainingConsumers = countAffectedConsumers(remainingAffected);
            String restorationStatus = "LINE_REPAIR_COMPLETED";
            restorationStatus += "; TRAVEL_TIME="
                    + report.getTravelTimeMinutes() + "m; REPAIR_TIME="
                    + report.getRepairTimeMinutes() + "m; ESTIMATED_RESTORATION="
                    + report.getEstimatedRestorationTimeMinutes() + "m";
            if (report.areAffectedConsumersRestored()) {
                restorationStatus += "; AFFECTED_CONSUMERS_RESTORED";
            }
            if (networkRestored) {
                restorationStatus += "; NETWORK_RESTORED";
            }
            logRestorationEvent(
                    "REPAIR_COMPLETED",
                    failedLine.getId(),
                    affectedZones(report),
                    team.getName(),
                    restorationStatus,
                    historyFailures);
            System.out.println("Running final BFS restoration verification...");
            System.out.println("Line repair completed: " + report.isLineRepairCompleted());
            System.out.println("Affected consumers restored: "
                    + report.areAffectedConsumersRestored());
            System.out.println("Entire network restored: " + networkRestored);
            if (networkRestored) {
                System.out.println("Restoration verified: all consumers are reachable and "
                        + "all failed lines are repaired.");
            } else {
                System.out.println("Network restoration incomplete: " + remainingConsumers
                        + " affected consumer area(s) remain disconnected; failed lines: "
                        + countFailedLines(network) + ".");
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
        return networkRestored;
    }

    private int countAffectedConsumers(List<NetworkNode> nodes) {
        int count = 0;
        for (NetworkNode node : nodes) {
            if (node instanceof ConsumerArea) {
                count++;
            }
        }
        return count;
    }

    private int countFailedLines(DistributionNetwork network) {
        int count = 0;
        for (NetworkLine line : network.getLines()) {
            if (line.getStatus() == LineStatus.FAILED) {
                count++;
            }
        }
        return count;
    }

    public int estimateRepairTimeMinutes(
            DistributionNetwork network, RepairTeam team, NetworkLine failedLine) {
        if (team == null || failedLine == null) {
            throw new IllegalArgumentException("team and failedLine must not be null.");
        }
        return Math.addExact(failedLine.getRepairTimeMinutes(),
                estimateTravelTimeMinutes(team, failedLine));
    }

    public int estimateTravelTimeMinutes(RepairTeam team, NetworkLine failedLine) {
        if (team == null || failedLine == null) {
            throw new IllegalArgumentException("team and failedLine must not be null.");
        }
        return team.getHomeDistrict().travelMinutesTo(failedLine.getDistrict());
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

    public synchronized List<BackupLinkProposal> getBackupLinkProposals() {
        return new ArrayList<>(backupProposals.values());
    }

    public synchronized boolean hasPendingBackupApprovalDecision() {
        for (BackupLinkProposal proposal : backupProposals.values()) {
            if (proposal.getApprovalStatus() == BackupApprovalStatus.PROPOSED) {
                return true;
            }
        }
        return false;
    }

    public synchronized List<BackupLinkProposal> proposeBackupLinks(
            DistributionNetwork network, OutageDetector detector, FaultReport report) {
        if (network == null || detector == null || report == null) {
            throw new IllegalArgumentException("network, detector, and report must not be null.");
        }
        if (report.getFailedLine().getStatus() != LineStatus.FAILED
                || report.getFailedLine().isBackupLink()) {
            throw new IllegalArgumentException("Backup proposals require a failed primary line.");
        }

        Set<NetworkNode> initiallyReachable = detector.findReachableNodes(network);
        Set<NetworkLine> candidateLines = new LinkedHashSet<>();
        for (List<NetworkLine> path : findBackupPaths(
                network, initiallyReachable, new LinkedHashSet<>())) {
            candidateLines.addAll(path);
        }
        List<NetworkLine> orderedCandidates = new ArrayList<>(candidateLines);
        orderedCandidates.sort(Comparator.comparing(NetworkLine::getId));

        List<BackupLinkProposal> created = new ArrayList<>();
        for (NetworkLine backupLine : orderedCandidates) {
            if (!backupLine.isBackupLink() || backupLine.getStatus() != LineStatus.STANDBY) {
                continue;
            }
            BackupApprovalStatus state = backupLine.getBackupApprovalStatus();
            if (state != BackupApprovalStatus.STANDBY) {
                continue;
            }

            Set<NetworkLine> trialLinks = new LinkedHashSet<>();
            trialLinks.add(backupLine);
            Set<NetworkNode> reachableWithLink =
                    detector.findReachableNodes(network, trialLinks);
            List<NetworkNode> restoredConsumers = new ArrayList<>();
            for (NetworkNode affected : report.getAffectedNodes()) {
                if (affected instanceof ConsumerArea
                        && !initiallyReachable.contains(affected)
                        && reachableWithLink.contains(affected)) {
                    restoredConsumers.add(affected);
                }
            }
            if (restoredConsumers.isEmpty()) {
                continue;
            }

            backupLine.proposeBackup();
            List<String> restorationPath = detector.findPathToNode(
                    network, trialLinks, restoredConsumers.get(0).getId());
            BackupLinkProposal proposal = new BackupLinkProposal(
                    backupLine, report.getFailedLine().getId(),
                    restorationPath, restoredConsumers);
            backupProposals.put(backupLine.getId(), proposal);
            created.add(proposal);
            recordBackupEvent("BACKUP_LINK_PROPOSED", proposal,
                    "PROPOSED_FOR=" + report.getFailedLine().getId());
        }
        return created;
    }

    public synchronized void approveBackupLink(String backupLineId) {
        BackupLinkProposal proposal = requireCurrentProposal(backupLineId);
        ensureProposalFaultPending(proposal);
        proposal.getBackupLine().approveBackup();
        recordBackupEvent("BACKUP_LINK_APPROVED", proposal,
                "OPERATOR_APPROVED_FOR=" + proposal.getFailedLineId());
    }

    public synchronized void rejectBackupLink(String backupLineId) {
        BackupLinkProposal proposal = requireCurrentProposal(backupLineId);
        ensureProposalFaultPending(proposal);
        proposal.getBackupLine().rejectBackup();
        recordBackupEvent("BACKUP_LINK_REJECTED", proposal,
                "OPERATOR_REJECTED_FOR=" + proposal.getFailedLineId());
    }

    public synchronized int activateApprovedBackupLink(
            String backupLineId, DistributionNetwork network, OutageDetector detector) {
        if (network == null || detector == null) {
            throw new IllegalArgumentException("network and detector must not be null.");
        }
        BackupLinkProposal proposal = requireCurrentProposal(backupLineId);
        ensureProposalFaultPending(proposal);
        NetworkLine backupLine = proposal.getBackupLine();
        if (network.getLine(backupLineId) != backupLine || !backupLine.isBackupLink()) {
            throw new IllegalArgumentException("The proposal does not belong to this network.");
        }
        backupLine.activateBackup();
        recordBackupEvent("BACKUP_LINK_ACTIVATED", proposal,
                "OPERATOR_APPROVED; ACTIVE_IN_SIMULATION");

        List<NetworkNode> affected = detector.findAffectedNodes(network);
        Set<NetworkNode> reachable = detector.findReachableNodes(network);
        int reconnectedConsumers = 0;
        for (NetworkNode consumer : proposal.getAffectedConsumers()) {
            if (reachable.contains(consumer) && consumer instanceof ConsumerArea) {
                reconnectedConsumers += ((ConsumerArea) consumer).getConsumerCount();
            }
        }
        int remainingAffectedConsumers = 0;
        for (NetworkNode node : affected) {
            if (node instanceof ConsumerArea) {
                remainingAffectedConsumers += ((ConsumerArea) node).getConsumerCount();
            }
        }
        updateRestorationStatuses(network, reachable);
        recordBackupEvent("BACKUP_BFS_VERIFICATION", proposal,
                "RECONNECTED_CONSUMERS=" + reconnectedConsumers
                        + "; REMAINING_AFFECTED_CONSUMERS="
                        + remainingAffectedConsumers + "; AFFECTED_NODES=" + affected.size());
        return remainingAffectedConsumers;
    }

    private BackupLinkProposal requireCurrentProposal(String backupLineId) {
        if (backupLineId == null || backupLineId.trim().isEmpty()) {
            throw new IllegalArgumentException("backupLineId must not be empty.");
        }
        BackupLinkProposal proposal = backupProposals.get(backupLineId);
        if (proposal == null) {
            throw new IllegalArgumentException("No current proposal exists for " + backupLineId + ".");
        }
        if (!proposal.getBackupLineId().equals(backupLineId)
                || !proposal.getBackupLine().isBackupLink()) {
            throw new IllegalArgumentException("The proposal does not match a predefined backup line.");
        }
        return proposal;
    }

    private void ensureProposalFaultPending(BackupLinkProposal proposal) {
        NetworkLine failedLine = assignmentNetwork == null
                ? null : assignmentNetwork.getLine(proposal.getFailedLineId());
        if (failedLine == null || failedLine.getStatus() != LineStatus.FAILED
                || !isPendingFault(failedLine)) {
            throw new IllegalStateException(
                    "The outage for this backup proposal is no longer pending.");
        }
    }

    private void expireBackupProposals(FaultReport report) {
        List<String> expiredIds = new ArrayList<>();
        for (BackupLinkProposal proposal : backupProposals.values()) {
            if (proposal.getFailedLineId().equals(report.getFailedLine().getId())) {
                proposal.getBackupLine().clearBackupApproval();
                expiredIds.add(proposal.getBackupLineId());
            }
        }
        for (String id : expiredIds) {
            backupProposals.remove(id);
        }
    }

    private void recordBackupEvent(
            String eventType, BackupLinkProposal proposal, String status) {
        List<UncheckedIOException> failures = new ArrayList<>();
        String zones = String.join(", ", proposal.getAffectedConsumers().stream()
                .filter(node -> node instanceof ZonedArea)
                .map(node -> ((ZonedArea) node).getDistrict().getDisplayName())
                .distinct().sorted().collect(java.util.stream.Collectors.toList()));
        logRestorationEvent(eventType, proposal.getBackupLineId(), zones,
                "OPERATOR", status, failures);
        if (!failures.isEmpty()) {
            throw failures.get(0);
        }
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
        int assignmentLimit = Math.min(pendingReports.size(), repairTeams.size());
        Set<RepairTeam> reservedTeams = new HashSet<>();

        for (int i = 0; i < assignmentLimit; i++) {
            FaultReport report = pendingReports.get(i);
            RepairTeam assignedTeam = report.getAssignedTeam();
            if (assignedTeam == null) {
                continue;
            }
            if (!repairTeams.contains(assignedTeam) || !reservedTeams.add(assignedTeam)) {
                report.assignRepairTeam(null);
                report.setTeamStatus("WAITING");
                continue;
            }
            if (assignedTeam.isAvailable()) {
                assignedTeam.assign();
            }
            report.setTeamStatus(assignedTeam.getStatus().name());
        }

        for (int i = assignmentLimit; i < pendingReports.size(); i++) {
            FaultReport report = pendingReports.get(i);
            RepairTeam assignedTeam = report.getAssignedTeam();
            if (assignedTeam == null) {
                continue;
            }
            report.assignRepairTeam(null);
            report.setTeamStatus("WAITING");
            if (repairTeams.contains(assignedTeam)
                    && !reservedTeams.contains(assignedTeam)) {
                assignedTeam.release();
            }
        }

        for (int i = 0; i < assignmentLimit; i++) {
            FaultReport report = pendingReports.get(i);
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
            reservedTeams.add(assignment.team);
        }

        for (int i = assignmentLimit; i < pendingReports.size(); i++) {
            FaultReport report = pendingReports.get(i);
            int estimate = estimateWithAnyTeam(network, report.getFailedLine());
            if (estimate > 0) {
                report.setEstimatedRepairMinutes(estimate);
            }
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
                            || line.getBackupApprovalStatus() != BackupApprovalStatus.STANDBY
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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Set;

public class RestorationPolicy {
    private final PriorityQueue<FaultReport> faultQueue =
            new PriorityQueue<>(Comparator.comparingInt(FaultReport::getPriorityScore).reversed());
    private final List<FaultReport> history = new ArrayList<>();
    private final List<RepairTeam> repairTeams = new ArrayList<>();

    public RestorationPolicy() {
        repairTeams.add(new RepairTeam("RT1", "Alpha Crew"));
        repairTeams.add(new RepairTeam("RT2", "Bravo Crew"));
        repairTeams.add(new RepairTeam("RT3", "Charlie Crew"));
    }

    public void submitFault(FaultReport report) {
        faultQueue.add(report);
        history.add(report);
    }

    public int getPendingFaultCount() {
        return faultQueue.size();
    }

    public List<FaultReport> getHistory() {
        return new ArrayList<>(history);
    }

    public void reset() {
        faultQueue.clear();
        history.clear();
        for (RepairTeam team : repairTeams) {
            team.release();
        }
    }

    public boolean restoreNextFault(DistributionNetwork network, OutageDetector detector) {
        if (faultQueue.isEmpty()) {
            System.out.println("There are no faults waiting for restoration.");
            return false;
        }

        FaultReport report = faultQueue.remove();
        RepairTeam team = findAvailableTeam();
        if (team == null) {
            faultQueue.add(report);
            System.out.println("No repair team is currently available.");
            return false;
        }

        team.assign();
        report.assignRepairTeam(team);
        NetworkLine failedLine = report.getFailedLine();
        System.out.println("Selected fault: " + failedLine.getId()
                + " (priority " + report.getPriorityScore() + ")");
        System.out.println("Assigned repair team: " + team.getId() + " - " + team.getName());
        System.out.println("DFS searching for an approved backup path...");

        Set<NetworkNode> reachableBeforeBackup = detector.findReachableNodes(network);
        Set<String> reachableIds = getNodeIds(reachableBeforeBackup);
        Set<String> affectedIds = getNodeIds(report.getAffectedNodes());
        List<NetworkLine> backupPath = findBackupPath(network, failedLine, affectedIds, reachableIds);

        if (!backupPath.isEmpty()) {
            printBackupPath(network, failedLine, backupPath);
            for (NetworkLine backupLine : backupPath) {
                backupLine.activateBackup();
                System.out.println(backupLine.getId() + " status: " + backupLine.getStatus());
            }

            Set<NetworkNode> temporarilyReachable = detector.findReachableNodes(network);
            System.out.println("Backup activated. Temporarily restored critical areas:");
            boolean foundCriticalArea = false;
            for (NetworkNode node : report.getAffectedNodes()) {
                if (node instanceof CriticalArea && temporarilyReachable.contains(node)) {
                    System.out.println("- " + node.getName());
                    foundCriticalArea = true;
                }
            }
            if (!foundCriticalArea) {
                System.out.println("- No affected critical areas");
            }
            System.out.println("Temporarily reachable affected nodes:");
            for (NetworkNode node : report.getAffectedNodes()) {
                if (temporarilyReachable.contains(node)) {
                    System.out.println("- " + node.getId() + " - " + node.getName());
                }
            }
        } else {
            System.out.println("No approved backup path is available.");
        }

        failedLine.repair();
        System.out.println(failedLine.getId() + " status after repair: " + failedLine.getStatus());
        team.release();
        System.out.println(team.getId() + " is now AVAILABLE.");

        for (NetworkLine backupLine : backupPath) {
            backupLine.deactivateBackup();
            System.out.println(backupLine.getId() + " status: " + backupLine.getStatus());
        }

        Set<NetworkNode> finalReachable = detector.findReachableNodes(network);
        boolean allNodesRestored = finalReachable.size() == network.getNodes().size();
        System.out.println("Running final BFS restoration verification...");
        if (allNodesRestored) {
            report.markRestored();
            System.out.println("Restoration verified: all " + finalReachable.size()
                    + " network nodes are reachable.");
        } else {
            System.out.println("Restoration incomplete: " + (network.getNodes().size()
                    - finalReachable.size()) + " node(s) remain unreachable.");
        }
        return allNodesRestored;
    }

    private RepairTeam findAvailableTeam() {
        for (RepairTeam team : repairTeams) {
            if (team.isAvailable()) {
                return team;
            }
        }
        return null;
    }

    private Set<String> getNodeIds(Iterable<NetworkNode> nodes) {
        Set<String> ids = new HashSet<>();
        for (NetworkNode node : nodes) {
            ids.add(node.getId());
        }
        return ids;
    }

    private List<NetworkLine> findBackupPath(
            DistributionNetwork network,
            NetworkLine failedLine,
            Set<String> affectedIds,
            Set<String> reachableIds) {
        List<String> startingNodes = new ArrayList<>();
        if (affectedIds.contains(failedLine.getToNodeId())) {
            startingNodes.add(failedLine.getToNodeId());
        }
        if (affectedIds.contains(failedLine.getFromNodeId())) {
            startingNodes.add(failedLine.getFromNodeId());
        }

        for (String startId : startingNodes) {
            List<NetworkLine> path = depthFirstSearch(network, startId, reachableIds);
            if (!path.isEmpty()) {
                return path;
            }
        }
        return new ArrayList<>();
    }

    private List<NetworkLine> depthFirstSearch(
            DistributionNetwork network, String startId, Set<String> reachableIds) {
        Deque<PathStep> stack = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        stack.push(new PathStep(startId, new ArrayList<>()));

        while (!stack.isEmpty()) {
            PathStep step = stack.pop();
            if (!visited.add(step.nodeId)) {
                continue;
            }
            if (!step.path.isEmpty() && reachableIds.contains(step.nodeId)) {
                return step.path;
            }

            List<NetworkLine> incidentLines = network.getIncidentLines(step.nodeId);
            for (int i = incidentLines.size() - 1; i >= 0; i--) {
                NetworkLine line = incidentLines.get(i);
                if (!line.isBackupLink() || line.getStatus() != LineStatus.STANDBY) {
                    continue;
                }
                String nextId = network.getOtherNodeId(line, step.nodeId);
                if (!visited.contains(nextId)) {
                    List<NetworkLine> nextPath = new ArrayList<>(step.path);
                    nextPath.add(line);
                    stack.push(new PathStep(nextId, nextPath));
                }
            }
        }

        return new ArrayList<>();
    }

    private void printBackupPath(
            DistributionNetwork network, NetworkLine failedLine, List<NetworkLine> path) {
        String currentId = affectedEndpoint(failedLine);
        System.out.print("Backup path: " + currentId);
        for (NetworkLine line : path) {
            currentId = network.getOtherNodeId(line, currentId);
            System.out.print(" -> " + currentId + " (" + line.getId() + ")");
        }
        System.out.println();
    }

    private String affectedEndpoint(NetworkLine failedLine) {
        for (FaultReport report : history) {
            if (report.getFailedLine() == failedLine) {
                Set<String> affectedIds = getNodeIds(report.getAffectedNodes());
                if (affectedIds.contains(failedLine.getToNodeId())) {
                    return failedLine.getToNodeId();
                }
                if (affectedIds.contains(failedLine.getFromNodeId())) {
                    return failedLine.getFromNodeId();
                }
            }
        }
        return failedLine.getToNodeId();
    }

    private static class PathStep {
        private final String nodeId;
        private final List<NetworkLine> path;

        private PathStep(String nodeId, List<NetworkLine> path) {
            this.nodeId = nodeId;
            this.path = path;
        }
    }
}

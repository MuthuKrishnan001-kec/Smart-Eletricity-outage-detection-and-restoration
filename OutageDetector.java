import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

public class OutageDetector {
    public Set<NetworkNode> findReachableNodes(DistributionNetwork network) {
        return findReachableNodes(network, new HashSet<>());
    }

    public Set<NetworkNode> findReachableNodes(
            DistributionNetwork network, Set<NetworkLine> activatedBackupLines) {
        if (network == null) {
            throw new IllegalArgumentException("network must not be null.");
        }
        if (activatedBackupLines == null) {
            throw new IllegalArgumentException("activatedBackupLines must not be null.");
        }
        Set<NetworkNode> reachable = new HashSet<>();
        Set<String> visitedIds = new HashSet<>();
        Queue<String> queue = new ArrayDeque<>();

        for (Substation substation : network.getSubstations()) {
            if (substation.isPowered() && visitedIds.add(substation.getId())) {
                reachable.add(substation);
                queue.add(substation.getId());
            }
        }

        while (!queue.isEmpty()) {
            String currentId = queue.remove();
            for (NetworkLine line : network.getIncidentLines(currentId)) {
                boolean virtuallyActivated = line.isBackupLink()
                        && line.getStatus() == LineStatus.STANDBY
                        && activatedBackupLines.contains(line);
                if (!line.isWorking() && !virtuallyActivated) {
                    continue;
                }
                String nextId = network.getOtherNodeId(line, currentId);
                if (visitedIds.add(nextId)) {
                    NetworkNode nextNode = network.getNode(nextId);
                    reachable.add(nextNode);
                    queue.add(nextId);
                }
            }
        }

        return reachable;
    }

    public List<String> findPathToNode(
            DistributionNetwork network,
            Set<NetworkLine> activatedBackupLines,
            String targetNodeId) {
        if (network == null || activatedBackupLines == null || targetNodeId == null) {
            throw new IllegalArgumentException(
                    "network, activatedBackupLines, and targetNodeId must not be null.");
        }
        if (network.getNode(targetNodeId) == null) {
            throw new IllegalArgumentException("Unknown target node: " + targetNodeId);
        }

        Queue<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        Map<String, String> previousNode = new HashMap<>();
        Map<String, String> previousLine = new HashMap<>();
        for (Substation substation : network.getSubstations()) {
            if (substation.isPowered() && visited.add(substation.getId())) {
                queue.add(substation.getId());
            }
        }

        while (!queue.isEmpty() && !visited.contains(targetNodeId)) {
            String currentId = queue.remove();
            for (NetworkLine line : network.getIncidentLines(currentId)) {
                boolean virtuallyActivated = line.isBackupLink()
                        && line.getStatus() == LineStatus.STANDBY
                        && activatedBackupLines.contains(line);
                if (!line.isWorking() && !virtuallyActivated) {
                    continue;
                }
                String nextId = network.getOtherNodeId(line, currentId);
                if (visited.add(nextId)) {
                    previousNode.put(nextId, currentId);
                    previousLine.put(nextId, line.getId());
                    queue.add(nextId);
                }
            }
        }

        if (!visited.contains(targetNodeId)) {
            return new ArrayList<>();
        }
        List<String> reversedPath = new ArrayList<>();
        String cursor = targetNodeId;
        reversedPath.add(cursor);
        while (previousNode.containsKey(cursor)) {
            reversedPath.add(previousLine.get(cursor));
            cursor = previousNode.get(cursor);
            reversedPath.add(cursor);
        }
        Collections.reverse(reversedPath);
        return reversedPath;
    }

    public OutageDiagnostics detectFaults(
            DistributionNetwork network, SensorMonitor sensorMonitor) {
        if (network == null || sensorMonitor == null) {
            throw new IllegalArgumentException("network and sensorMonitor must not be null.");
        }
        Set<NetworkLine> faultedLines = sensorMonitor.findFaultedLines(network.getLines());
        List<NetworkNode> affectedNodes = findAffectedNodes(network);
        Set<ConsumerArea> disconnectedConsumerAreas = new LinkedHashSet<>();
        Set<CriticalArea> affectedCriticalFacilities = new LinkedHashSet<>();

        for (NetworkNode node : affectedNodes) {
            if (node instanceof ConsumerArea) {
                disconnectedConsumerAreas.add((ConsumerArea) node);
            }
            if (node instanceof CriticalArea) {
                affectedCriticalFacilities.add((CriticalArea) node);
            }
        }
        return new OutageDiagnostics(
                faultedLines, affectedNodes, disconnectedConsumerAreas,
                affectedCriticalFacilities);
    }

    public List<NetworkNode> findAffectedNodes(DistributionNetwork network) {
        Set<NetworkNode> reachable = findReachableNodes(network);
        List<NetworkNode> affected = new ArrayList<>();
        for (NetworkNode node : network.getNodes()) {
            boolean isReachable = reachable.contains(node);
            if (!(node instanceof Substation)) {
                node.setPowered(isReachable);
            }
            if (!isReachable) {
                affected.add(node);
            }
        }
        return affected;
    }
}

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public class OutageDetector {
    public Set<NetworkNode> findReachableNodes(DistributionNetwork network) {
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
                if (!line.isWorking()) {
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

    public List<NetworkNode> findAffectedNodes(DistributionNetwork network) {
        Set<NetworkNode> reachable = findReachableNodes(network);
        List<NetworkNode> affected = new ArrayList<>();
        for (NetworkNode node : network.getNodes()) {
            if (!reachable.contains(node)) {
                affected.add(node);
            }
        }
        return affected;
    }
}

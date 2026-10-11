import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class OutageDiagnostics {
    private final Set<NetworkLine> faultedLines;
    private final List<NetworkNode> affectedNodes;
    private final Set<ConsumerArea> disconnectedConsumerAreas;
    private final Set<CriticalArea> affectedCriticalFacilities;

    public OutageDiagnostics(
            Set<NetworkLine> faultedLines,
            List<NetworkNode> affectedNodes,
            Set<ConsumerArea> disconnectedConsumerAreas,
            Set<CriticalArea> affectedCriticalFacilities) {
        this.faultedLines = Collections.unmodifiableSet(
                new LinkedHashSet<>(faultedLines));
        this.affectedNodes = Collections.unmodifiableList(
                new ArrayList<>(affectedNodes));
        this.disconnectedConsumerAreas = Collections.unmodifiableSet(
                new LinkedHashSet<>(disconnectedConsumerAreas));
        this.affectedCriticalFacilities = Collections.unmodifiableSet(
                new LinkedHashSet<>(affectedCriticalFacilities));
    }

    public Set<NetworkLine> getFaultedLines() {
        return faultedLines;
    }

    public List<NetworkNode> getAffectedNodes() {
        return affectedNodes;
    }

    public Set<ConsumerArea> getDisconnectedConsumerAreas() {
        return disconnectedConsumerAreas;
    }

    public Set<CriticalArea> getAffectedCriticalFacilities() {
        return affectedCriticalFacilities;
    }
}

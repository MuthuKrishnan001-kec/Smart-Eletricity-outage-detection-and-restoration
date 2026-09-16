import java.util.Objects;

public class NetworkEdge {
    private final String fromNodeId;
    private final String toNodeId;
    private final String id;
    private boolean active;
    private int capacity;

    public NetworkEdge(String fromNodeId, String toNodeId, boolean active, int capacity) {
        this.fromNodeId = Objects.requireNonNull(fromNodeId);
        this.toNodeId = Objects.requireNonNull(toNodeId);
        this.active = active;
        this.capacity = capacity;
        this.id = fromNodeId + "->" + toNodeId;
    }

    public String getId() {
        return id;
    }

    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getCapacity() {
        return capacity;
    }

    public void setCapacity(int capacity) {
        this.capacity = capacity;
    }

    @Override
    public String toString() {
        return String.format("Edge %s -> %s [active=%s, cap=%d]",
                fromNodeId, toNodeId, active, capacity);
    }
}

public abstract class NetworkNode {
    private String id;
    private String name;
    private boolean powered;

    public NetworkNode(String id, String name, boolean powered) {
        this.id = id;
        this.name = name;
        this.powered = powered;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isPowered() {
        return powered;
    }

    public void setPowered(boolean powered) {
        this.powered = powered;
    }

    public abstract String getNodeLabel();
}

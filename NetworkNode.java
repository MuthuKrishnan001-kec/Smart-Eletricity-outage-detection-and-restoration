public class NetworkNode {
    private final String id;
    private String name;
    private NodeType type;
    private boolean powerStatus;
    private CustomerType customerType;

    public NetworkNode(String id, String name, NodeType type, boolean powerStatus) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.powerStatus = powerStatus;
        this.customerType = null;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public NodeType getType() {
        return type;
    }

    public boolean isPowered() {
        return powerStatus;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public void setCustomerType(CustomerType customerType) {
        this.customerType = customerType;
    }

    public void setPowered(boolean powerStatus) {
        this.powerStatus = powerStatus;
    }

    public CustomerType getCustomerType() {
        return customerType;
    }

    @Override
    public String toString() {
        String base = String.format(
                "ID: %s%nName: %s%nType: %s%nPower Status: %s",
                id,
                name,
                type,
                powerStatus ? "ON" : "OFF"
        );
        if (type == NodeType.CUSTOMER && customerType != null) {
            base += String.format("%nCustomer Type: %s", customerType);
        }
        return base + "\n";
    }
}
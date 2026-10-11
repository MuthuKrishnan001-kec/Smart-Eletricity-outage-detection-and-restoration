public class RepairTeam {
    private final String id;
    private final String name;
    private final String depotNodeId;
    private RepairTeamStatus status;

    public RepairTeam(String id, String name) {
        this(id, name, null);
    }

    public RepairTeam(String id, String name, String depotNodeId) {
        this.id = id;
        this.name = name;
        this.depotNodeId = depotNodeId;
        this.status = RepairTeamStatus.AVAILABLE;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDepotNodeId() {
        return depotNodeId;
    }

    public RepairTeamStatus getStatus() {
        return status;
    }

    public boolean isAvailable() {
        return status == RepairTeamStatus.AVAILABLE;
    }

    public void assign() {
        if (!isAvailable()) {
            throw new IllegalStateException("Repair team is already assigned.");
        }
        status = RepairTeamStatus.BUSY;
    }

    public void release() {
        status = RepairTeamStatus.AVAILABLE;
    }
}

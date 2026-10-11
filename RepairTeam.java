public class RepairTeam {
    private final String id;
    private final String name;
    private RepairTeamStatus status;

    public RepairTeam(String id, String name) {
        this.id = id;
        this.name = name;
        this.status = RepairTeamStatus.AVAILABLE;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
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
        status = RepairTeamStatus.ASSIGNED;
    }

    public void release() {
        status = RepairTeamStatus.AVAILABLE;
    }
}

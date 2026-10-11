public class RepairTeam {
    private final String id;
    private final String name;
    private final String depotNodeId;
    private final String homeLocation;
    private final CityDistrict homeDistrict;
    private RepairTeamStatus status;

    public RepairTeam(String id, String name) {
        this(id, name, null, "Central Depot", CityDistrict.DOWNTOWN);
    }

    public RepairTeam(String id, String name, String depotNodeId) {
        this(id, name, depotNodeId, depotNameForNode(depotNodeId),
                districtForNode(depotNodeId));
    }

    public RepairTeam(
            String id,
            String name,
            String homeLocation,
            CityDistrict homeDistrict) {
        this(id, name, null, homeLocation, homeDistrict);
    }

    private RepairTeam(
            String id,
            String name,
            String depotNodeId,
            String homeLocation,
            CityDistrict homeDistrict) {
        if (id == null || id.trim().isEmpty()
                || name == null || name.trim().isEmpty()
                || homeLocation == null || homeLocation.trim().isEmpty()
                || homeDistrict == null) {
            throw new IllegalArgumentException(
                    "Team identity and home location must not be empty.");
        }
        this.id = id;
        this.name = name;
        this.depotNodeId = depotNodeId;
        this.homeLocation = homeLocation;
        this.homeDistrict = homeDistrict;
        this.status = RepairTeamStatus.AVAILABLE;
    }

    private static String depotNameForNode(String depotNodeId) {
        if ("S1".equals(depotNodeId)) {
            return "North Depot";
        }
        if ("S2".equals(depotNodeId)) {
            return "South Depot";
        }
        return "Central Depot";
    }

    private static CityDistrict districtForNode(String depotNodeId) {
        if ("S1".equals(depotNodeId)) {
            return CityDistrict.NORTH;
        }
        if ("S2".equals(depotNodeId)) {
            return CityDistrict.SOUTH;
        }
        return CityDistrict.DOWNTOWN;
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

    public String getHomeLocation() {
        return homeLocation;
    }

    public CityDistrict getHomeDistrict() {
        return homeDistrict;
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

public enum CityDistrict {
    NORTH("North"),
    SOUTH("South"),
    DOWNTOWN("Downtown");

    private final String displayName;

    CityDistrict(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public int travelMinutesTo(CityDistrict other) {
        if (other == null) {
            throw new IllegalArgumentException("other district must not be null.");
        }
        if (this == other) {
            return 15;
        }
        if ((this == NORTH && other == DOWNTOWN)
                || (this == DOWNTOWN && other == NORTH)
                || (this == SOUTH && other == DOWNTOWN)
                || (this == DOWNTOWN && other == SOUTH)) {
            return 25;
        }
        return 40;
    }
}

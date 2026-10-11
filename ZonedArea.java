import java.util.Objects;

public class ZonedArea extends ConsumerArea {
    private final ConsumerZone zone;
    private final CityDistrict district;

    public ZonedArea(
            String id,
            String name,
            boolean powered,
            int consumerCount,
            ConsumerZone zone,
            CityDistrict district) {
        super(id, name, powered, consumerCount);
        this.zone = Objects.requireNonNull(zone, "zone");
        this.district = Objects.requireNonNull(district, "district");
    }

    public ConsumerZone getZone() {
        return zone;
    }

    public CityDistrict getDistrict() {
        return district;
    }

    @Override
    public String getNodeLabel() {
        return zone.name().charAt(0) + zone.name().substring(1).toLowerCase() + " Area";
    }

    @Override
    public int getPriorityWeight() {
        return zone.getPriorityWeight();
    }
}

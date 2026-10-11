public class CriticalFacility extends CriticalArea {
    public CriticalFacility(String id, String name, boolean powered) {
        super(id, name, powered, 1);
    }

    @Override
    public String getNodeLabel() {
        return "Critical Facility";
    }

    @Override
    public int getPriorityWeight() {
        return 100;
    }
}

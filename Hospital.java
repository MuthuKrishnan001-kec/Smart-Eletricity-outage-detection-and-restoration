public class Hospital extends CriticalArea {
    public Hospital(String id, String name, boolean powered, int consumerCount) {
        super(id, name, powered, consumerCount);
    }

    @Override
    public String getNodeLabel() {
        return "Hospital";
    }

    @Override
    public int getPriorityWeight() {
        return 100;
    }
}

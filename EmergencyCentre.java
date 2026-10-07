public class EmergencyCentre extends CriticalArea {
    public EmergencyCentre(String id, String name, boolean powered, int consumerCount) {
        super(id, name, powered, consumerCount);
    }

    @Override
    public String getNodeLabel() {
        return "Emergency Centre";
    }

    @Override
    public int getPriorityWeight() {
        return 90;
    }
}

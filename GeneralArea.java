public class GeneralArea extends ConsumerArea {
    public GeneralArea(String id, String name, boolean powered, int consumerCount) {
        super(id, name, powered, consumerCount);
    }

    @Override
    public String getNodeLabel() {
        return "General Area";
    }

    @Override
    public int getPriorityWeight() {
        return 10;
    }
}

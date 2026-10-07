public class Feeder extends NetworkNode {
    public Feeder(String id, String name, boolean powered) {
        super(id, name, powered);
    }

    @Override
    public String getNodeLabel() {
        return "Feeder";
    }
}

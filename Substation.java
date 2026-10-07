public class Substation extends NetworkNode {
    public Substation(String id, String name, boolean powered) {
        super(id, name, powered);
    }

    @Override
    public String getNodeLabel() {
        return "Substation";
    }
}

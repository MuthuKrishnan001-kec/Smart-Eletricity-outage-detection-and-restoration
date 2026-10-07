public abstract class ConsumerArea extends NetworkNode {
    private int consumerCount;

    public ConsumerArea(String id, String name, boolean powered, int consumerCount) {
        super(id, name, powered);
        this.consumerCount = consumerCount;
    }

    public int getConsumerCount() {
        return consumerCount;
    }

    public abstract int getPriorityWeight();
}

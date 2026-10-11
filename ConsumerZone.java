public enum ConsumerZone {
    INDUSTRIAL(50),
    COMMERCIAL(30),
    RESIDENTIAL(10);

    private final int priorityWeight;

    ConsumerZone(int priorityWeight) {
        this.priorityWeight = priorityWeight;
    }

    public int getPriorityWeight() {
        return priorityWeight;
    }
}

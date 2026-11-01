public final class SafetyNotice {
    public static final String TEXT = "SAFETY NOTICE: Software-based electricity network simulation only. "
            + "BFS and graph connectivity identify possible network paths only; this system does "
            + "not calculate actual voltage, current, load flow, line capacity, or protection "
            + "coordination. A proposed backup path is not a real-world switching instruction. "
            + "Simulated operator approval is not electrical authorization. Never use this software "
            + "to operate real electrical equipment.";

    private SafetyNotice() {
    }
}

/** Validation shared by operator repair-time entry points. */
public final class RepairTimeInput {
    private RepairTimeInput() {
    }

    public static int parsePositiveMinutes(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Repair time must be a positive whole number of minutes.");
        }
        String trimmed = value.trim();
        if (!trimmed.matches("[0-9]+")) {
            throw new IllegalArgumentException(
                    "Repair time must be a positive whole number of minutes.");
        }
        try {
            int minutes = Integer.parseInt(trimmed);
            if (minutes <= 0) {
                throw new IllegalArgumentException(
                        "Repair time must be greater than zero minutes.");
            }
            return minutes;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Repair time must be a positive whole number of minutes.", exception);
        }
    }
}

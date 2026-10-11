import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class HistoryManager {
    private static final String[] HEADERS = {
        "Timestamp", "Event Type", "Line ID", "Affected Zones", "Assigned Team", "Status"
    };

    private final Path historyFile;

    public HistoryManager() {
        this(Paths.get("outage_history.csv"));
    }

    public HistoryManager(Path historyFile) {
        this.historyFile = Objects.requireNonNull(historyFile, "historyFile");
    }

    public synchronized void logEvent(
            String timestamp,
            String eventType,
            String lineId,
            String affectedZones,
            String assignedTeam,
            String status) throws IOException {
        String[] values = {
            timestamp, eventType, lineId, affectedZones, assignedTeam, status
        };
        for (String value : values) {
            Objects.requireNonNull(value, "CSV event values must not be null.");
        }

        ensureHeader();
        try (BufferedWriter writer = Files.newBufferedWriter(
                historyFile, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
            writeRecord(writer, values);
            writer.newLine();
        }
    }

    public synchronized List<String[]> loadHistory() throws IOException {
        ensureHeader();
        List<String[]> events = new ArrayList<>();
        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(Files.newBufferedReader(
                        historyFile, StandardCharsets.UTF_8)), 1)) {
            readRecord(reader);
            String[] record;
            while ((record = readRecord(reader)) != null) {
                if (record.length != HEADERS.length) {
                    throw new IOException("Invalid history CSV record: expected "
                            + HEADERS.length + " columns but found " + record.length + ".");
                }
                events.add(record);
            }
        }
        return events;
    }

    public static String[] getHeaders() {
        return HEADERS.clone();
    }

    private void ensureHeader() throws IOException {
        Path parent = historyFile.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (!Files.exists(historyFile) || Files.size(historyFile) == 0) {
            try (BufferedWriter writer = Files.newBufferedWriter(
                    historyFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeRecord(writer, HEADERS);
                writer.newLine();
            }
        }
    }

    private static void writeRecord(BufferedWriter writer, String[] values)
            throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                writer.write(',');
            }
            String value = values[i];
            boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                    || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
            if (quote) {
                writer.write('"');
            }
            for (int j = 0; j < value.length(); j++) {
                char character = value.charAt(j);
                if (character == '"') {
                    writer.write("\"\"");
                } else {
                    writer.write(character);
                }
            }
            if (quote) {
                writer.write('"');
            }
        }
    }

    private static String[] readRecord(PushbackReader reader) throws IOException {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean hasCharacters = false;
        int character;

        while ((character = reader.read()) != -1) {
            hasCharacters = true;
            if (quoted) {
                if (character == '"') {
                    int next = reader.read();
                    if (next == '"') {
                        field.append('"');
                    } else {
                        quoted = false;
                        if (next != -1) {
                            reader.unread(next);
                        }
                    }
                } else {
                    field.append((char) character);
                }
            } else if (character == '"' && field.length() == 0) {
                quoted = true;
            } else if (character == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else if (character == '\n' || character == '\r') {
                if (character == '\r') {
                    int next = reader.read();
                    if (next != '\n' && next != -1) {
                        reader.unread(next);
                    }
                }
                fields.add(field.toString());
                return fields.toArray(new String[0]);
            } else {
                field.append((char) character);
            }
        }

        if (quoted) {
            throw new IOException("Invalid history CSV: unterminated quoted field.");
        }
        if (!hasCharacters && fields.isEmpty() && field.length() == 0) {
            return null;
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }
}

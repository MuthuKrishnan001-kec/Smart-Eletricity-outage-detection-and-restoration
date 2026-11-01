import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

public class StatusSummaryPanel extends JPanel {
    private static final Color INK = new Color(24, 39, 61);
    private static final Color MUTED = new Color(104, 119, 139);
    private static final Color BORDER = new Color(226, 232, 240);
    private final OutageSummaryModel outageModel = new OutageSummaryModel();
    private final RepairQueueModel repairQueueModel = new RepairQueueModel();
    private final JLabel restorationStatus = new JLabel("Entire network restored: YES");

    public StatusSummaryPanel() {
        super(new BorderLayout(0, 10));
        setBackground(new Color(246, 248, 251));
        restorationStatus.setOpaque(true);
        restorationStatus.setBackground(Color.WHITE);
        restorationStatus.setForeground(INK);
        restorationStatus.setFont(restorationStatus.getFont().deriveFont(Font.BOLD, 12f));
        restorationStatus.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)));
        add(restorationStatus, BorderLayout.NORTH);

        JPanel tables = new JPanel(new GridLayout(2, 1, 0, 14));
        tables.setOpaque(false);
        tables.add(createTablePanel("OUTAGE SUMMARY", createTable(outageModel)));
        tables.add(createTablePanel("FAULT REPAIR & RESTORATION TRACKING",
                createTable(repairQueueModel)));
        add(tables, BorderLayout.CENTER);
    }

    public void refreshData(
            DistributionNetwork network, PriorityQueue<FaultReport> queue) {
        if (network == null || queue == null) {
            throw new IllegalArgumentException("network and queue must not be null.");
        }
        outageModel.refresh(network);
        repairQueueModel.refresh(queue);
    }

    public void refreshData(
            DistributionNetwork network,
            RestorationPolicy policy,
            OutageDetector detector) {
        if (network == null || policy == null || detector == null) {
            throw new IllegalArgumentException(
                    "network, policy, and detector must not be null.");
        }
        boolean fullyRestored = policy.isNetworkFullyRestored(network, detector);
        outageModel.refresh(network);
        repairQueueModel.refresh(policy.getFaultReports());
        restorationStatus.setText("Entire network restored: "
                + (fullyRestored ? "YES" : "NO"));
    }

    private JTable createTable(AbstractTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(34);
        table.setFont(table.getFont().deriveFont(12.5f));
        table.setForeground(INK);
        table.setBackground(Color.WHITE);
        table.setGridColor(new Color(237, 241, 246));
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setSelectionBackground(new Color(219, 234, 254));
        table.setSelectionForeground(INK);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(
                table.getTableHeader().getFont().deriveFont(Font.BOLD, 11f));
        table.getTableHeader().setForeground(MUTED);
        table.getTableHeader().setBackground(new Color(247, 249, 252));
        table.getTableHeader().setBorder(BorderFactory.createMatteBorder(
                0, 0, 1, 0, BORDER));
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        table.setDefaultRenderer(Object.class, new StatusRenderer());
        return table;
    }

    private JPanel createTablePanel(String title, JTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 12));
        panel.setBackground(Color.WHITE);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                BorderFactory.createEmptyBorder(16, 14, 14, 14)));
        JLabel heading = new JLabel(title);
        heading.setForeground(MUTED);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 11f));
        panel.add(heading, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(380, 250));
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER));
        scrollPane.getViewport().setBackground(Color.WHITE);
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    private class OutageSummaryModel extends AbstractTableModel {
        private final String[] columns = {
            "Node ID", "Name", "Type", "Powered Status", "Priority"
        };
        private final List<Object[]> rows = new ArrayList<>();

        private void refresh(DistributionNetwork network) {
            rows.clear();
            for (NetworkNode node : network.getNodes()) {
                String priority = node instanceof ConsumerArea
                        ? Integer.toString(((ConsumerArea) node).getPriorityWeight())
                        : "-";
                rows.add(new Object[] {
                    node.getId(),
                    node.getName(),
                    node.getNodeLabel(),
                    node.isPowered() ? "POWERED" : "AFFECTED",
                    priority
                });
            }
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex)[columnIndex];
        }
    }

    private class RepairQueueModel extends AbstractTableModel {
        private final String[] columns = {
            "Line ID", "Line state", "Line repair", "Affected consumers",
            "Critical facilities", "Repair team", "Repair / Travel / ETA (min)"
        };
        private final List<Object[]> rows = new ArrayList<>();

        private void refresh(PriorityQueue<FaultReport> queue) {
            rows.clear();
            PriorityQueue<FaultReport> orderedQueue = new PriorityQueue<>(queue);
            while (!orderedQueue.isEmpty()) {
                addReportRow(orderedQueue.poll());
            }
            fireTableDataChanged();
        }

        private void refresh(List<FaultReport> reports) {
            rows.clear();
            for (FaultReport report : reports) {
                addReportRow(report);
            }
            fireTableDataChanged();
        }

        private void addReportRow(FaultReport report) {
                RepairTeam team = report.getAssignedTeam();
                List<String> criticalFacilities = new ArrayList<>();
                int affectedConsumers = 0;
                for (NetworkNode node : report.getAffectedNodes()) {
                    if (node instanceof ConsumerArea) {
                        affectedConsumers++;
                    }
                    if (node instanceof CriticalArea) {
                        criticalFacilities.add(node.getId());
                    }
                }
                rows.add(new Object[] {
                    report.getFailedLine().getId(),
                    report.getFailedLine().getStatus(),
                    report.isLineRepairCompleted() ? "REPAIRED" : "PENDING",
                    affectedConsumers + " | " + (report.areAffectedConsumersRestored()
                            ? "RESTORED" : "DISCONNECTED"),
                    criticalFacilities.isEmpty() ? "-"
                            : String.join(", ", criticalFacilities),
                    team == null ? "Unassigned" : team.getId() + " - " + team.getName(),
                    report.getRepairTimeMinutes() + " / " + report.getTravelTimeMinutes()
                            + " / "
                            + report.getEstimatedRestorationTimeMinutes()
                });
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            return rows.get(rowIndex)[columnIndex];
        }
    }

    private class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                component.setBackground(row % 2 == 0 ? Color.WHITE
                        : new Color(249, 250, 252));
                component.setForeground(INK);
                if (column == 3 && "AFFECTED".equals(String.valueOf(value))) {
                    component.setBackground(new Color(254, 242, 242));
                    component.setForeground(new Color(185, 58, 58));
                }
            }
            if (component instanceof JLabel) {
                ((JLabel) component).setBorder(
                        BorderFactory.createEmptyBorder(0, 9, 0, 9));
            }
            return component;
        }
    }
}

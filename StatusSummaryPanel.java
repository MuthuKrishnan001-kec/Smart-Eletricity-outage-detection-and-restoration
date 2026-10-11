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
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

public class StatusSummaryPanel extends JPanel {
    private static final Color INK = new Color(38, 52, 67);
    private final OutageSummaryModel outageModel = new OutageSummaryModel();
    private final RepairQueueModel repairQueueModel = new RepairQueueModel();

    public StatusSummaryPanel() {
        super(new GridLayout(2, 1, 0, 10));
        setBackground(new Color(244, 247, 250));
        add(createTablePanel("OUTAGE SUMMARY", createTable(outageModel)));
        add(createTablePanel("REPAIR QUEUE & TEAM STATUS",
                createTable(repairQueueModel)));
    }

    public void refreshData(
            DistributionNetwork network, PriorityQueue<FaultReport> queue) {
        if (network == null || queue == null) {
            throw new IllegalArgumentException("network and queue must not be null.");
        }
        outageModel.refresh(network);
        repairQueueModel.refresh(queue);
    }

    private JTable createTable(AbstractTableModel model) {
        JTable table = new JTable(model);
        table.setFillsViewportHeight(true);
        table.setRowHeight(28);
        table.setFont(table.getFont().deriveFont(12f));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setFont(
                table.getTableHeader().getFont().deriveFont(Font.BOLD));
        table.setDefaultRenderer(Object.class, new StatusRenderer());
        return table;
    }

    private JPanel createTablePanel(String title, JTable table) {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBackground(new Color(244, 247, 250));
        JLabel heading = new JLabel(title);
        heading.setForeground(INK);
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 13f));
        panel.add(heading, BorderLayout.NORTH);
        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setPreferredSize(new Dimension(380, 250));
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(222, 229, 236)));
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
            "Line ID", "Fault Zone", "Priority", "Dispatched Team", "ETA (mins)"
        };
        private final List<Object[]> rows = new ArrayList<>();

        private void refresh(PriorityQueue<FaultReport> queue) {
            rows.clear();
            PriorityQueue<FaultReport> orderedQueue = new PriorityQueue<>(queue);
            while (!orderedQueue.isEmpty()) {
                FaultReport report = orderedQueue.poll();
                RepairTeam team = report.getAssignedTeam();
                rows.add(new Object[] {
                    report.getFailedLine().getId(),
                    report.getFailedLine().getFaultZone(),
                    report.getPriorityScore(),
                    team == null ? "Unassigned" : team.getId() + " - " + team.getName(),
                    report.getEstimatedRepairMinutes() == 0
                            ? "-" : report.getEstimatedRepairMinutes()
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

    private class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int column) {
            java.awt.Component component = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                component.setBackground(row % 2 == 0 ? Color.WHITE
                        : new Color(247, 249, 251));
                component.setForeground(INK);
                if (column == 3 && "AFFECTED".equals(String.valueOf(value))) {
                    component.setBackground(new Color(255, 235, 235));
                    component.setForeground(new Color(160, 40, 40));
                }
            }
            return component;
        }
    }
}

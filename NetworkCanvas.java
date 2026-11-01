import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.geom.Line2D;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.Map;
import javax.swing.SwingUtilities;
import javax.swing.JPanel;

public class NetworkCanvas extends JPanel {
    private static final Color POWERED_COLOR = new Color(48, 153, 97);
    private static final Color FAILED_COLOR = new Color(208, 65, 65);
    private static final Color BACKUP_COLOR = new Color(61, 126, 190);
    private static final Color INK = new Color(38, 52, 67);
    private static final int NODE_RADIUS = 25;

    private final DistributionNetwork network;
    private final OutageDetector outageDetector = new OutageDetector();
    private final Map<NetworkNode, Point> nodeCoordinates = new HashMap<>();
    private SensorMonitor sensorMonitor;
    private Consumer<NetworkLine> afterEdit = line -> { };
    private NetworkNode selectedNode;
    private NetworkLine selectedLine;

    public NetworkCanvas(DistributionNetwork network) {
        if (network == null) {
            throw new IllegalArgumentException("network must not be null.");
        }
        this.network = network;
        this.sensorMonitor = createDefaultSensorMonitor(network);
        setPreferredSize(new Dimension(1200, 880));
        setBackground(new Color(250, 252, 254));
        assignDefaultCoordinates();
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                NetworkNode node = findClickedNode(event.getPoint());
                if (node != null) {
                    openEditDialog(node, null);
                    return;
                }
                NetworkLine line = findClickedLine(event.getPoint());
                if (line != null) {
                    openEditDialog(null, line);
                }
            }
        });
    }

    public Map<NetworkNode, Point> getNodeCoordinates() {
        return Collections.unmodifiableMap(nodeCoordinates);
    }

    public Point getNodeCoordinate(NetworkNode node) {
        return nodeCoordinates.get(node);
    }

    public NetworkNode getNodeAt(Point point) {
        for (Map.Entry<NetworkNode, Point> entry : nodeCoordinates.entrySet()) {
            if (entry.getValue().distance(point) <= NODE_RADIUS + 8) {
                return entry.getKey();
            }
        }
        return null;
    }

    public NetworkNode findClickedNode(Point point) {
        if (point == null) {
            return null;
        }
        for (Map.Entry<NetworkNode, Point> entry : nodeCoordinates.entrySet()) {
            if (entry.getValue().distance(point) <= 15) {
                return entry.getKey();
            }
        }
        return null;
    }

    public NetworkLine getLineAt(Point point) {
        NetworkLine closest = null;
        double closestDistance = 12;
        for (NetworkLine line : network.getLines()) {
            Point from = getNodeCoordinate(line.getFromNodeId());
            Point to = getNodeCoordinate(line.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            double distance = Line2D.ptSegDist(
                    from.x, from.y, to.x, to.y, point.x, point.y);
            if (distance < closestDistance) {
                closest = line;
                closestDistance = distance;
            }
        }
        return closest;
    }

    public NetworkLine findClickedLine(Point point) {
        if (point == null) {
            return null;
        }
        NetworkLine closest = null;
        double closestDistance = 15;
        for (NetworkLine line : network.getLines()) {
            Point from = getNodeCoordinate(line.getFromNodeId());
            Point to = getNodeCoordinate(line.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            double distance = Line2D.ptSegDist(
                    from.x, from.y, to.x, to.y, point.x, point.y);
            if (distance <= closestDistance) {
                closest = line;
                closestDistance = distance;
            }
        }
        return closest;
    }

    public void setSensorMonitor(SensorMonitor sensorMonitor) {
        if (sensorMonitor == null) {
            throw new IllegalArgumentException("sensorMonitor must not be null.");
        }
        this.sensorMonitor = sensorMonitor;
    }

    public void setAfterEdit(Consumer<NetworkLine> afterEdit) {
        this.afterEdit = afterEdit == null ? line -> { } : afterEdit;
    }

    public void setSelectedElement(NetworkNode node, NetworkLine line) {
        selectedNode = node;
        selectedLine = line;
        repaint();
    }

    private void openEditDialog(NetworkNode node, NetworkLine line) {
        selectedNode = node;
        selectedLine = line;
        repaint();
        List<NetworkLine> incidentLines = new ArrayList<>();
        if (line != null) {
            incidentLines.add(line);
        } else {
            incidentLines.addAll(network.getIncidentLines(node.getId()));
        }
        Window owner = SwingUtilities.getWindowAncestor(this);
        EditStatusDialog dialog = new EditStatusDialog(
                owner, network, node, incidentLines, sensorMonitor, editedLine -> {
                    outageDetector.findAffectedNodes(network);
                    repaint();
                    afterEdit.accept(editedLine);
                });
        dialog.setVisible(true);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
        drawLines(g);
        drawNodes(g);
        drawLegend(g);
        g.dispose();
    }

    private void drawLines(Graphics2D g) {
        for (NetworkLine line : network.getLines()) {
            Point from = getNodeCoordinate(line.getFromNodeId());
            Point to = getNodeCoordinate(line.getToNodeId());
            if (from == null || to == null) {
                continue;
            }
            if (line == selectedLine) {
                g.setColor(new Color(255, 174, 48, 95));
                g.setStroke(new BasicStroke(11, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
                g.drawLine(from.x, from.y, to.x, to.y);
            }

            if (line.getStatus() == LineStatus.FAILED) {
                g.setColor(FAILED_COLOR);
                g.setStroke(new BasicStroke(4, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
            } else if (line.isBackupLink()
                    || line.getStatus() == LineStatus.STANDBY) {
                g.setColor(BACKUP_COLOR);
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND, 10, new float[] {9, 7}, 0));
            } else {
                g.setColor(POWERED_COLOR);
                g.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));
            }
            g.drawLine(from.x, from.y, to.x, to.y);
            drawLineLabel(g, line, from, to);
        }
    }

    private void drawLineLabel(Graphics2D g, NetworkLine line, Point from, Point to) {
        int labelX = (from.x + to.x) / 2;
        int labelY = (from.y + to.y) / 2 - 8;
        g.setFont(getFont().deriveFont(Font.BOLD, 11f));
        int labelWidth = g.getFontMetrics().stringWidth(line.getId());
        g.setColor(new Color(255, 255, 255, 225));
        g.fillRoundRect(labelX - labelWidth / 2 - 4, labelY - 12,
                labelWidth + 8, 17, 7, 7);
        g.setColor(line.getStatus() == LineStatus.FAILED
                ? FAILED_COLOR : new Color(66, 78, 91));
        g.drawString(line.getId(), labelX - labelWidth / 2, labelY);
    }

    private void drawNodes(Graphics2D g) {
        for (Map.Entry<NetworkNode, Point> entry : nodeCoordinates.entrySet()) {
            NetworkNode node = entry.getKey();
            Point point = entry.getValue();
            int radius = node instanceof CriticalArea ? NODE_RADIUS + 2 : NODE_RADIUS;
            if (node == selectedNode) {
                g.setColor(new Color(255, 174, 48, 110));
                g.fillOval(point.x - radius - 8, point.y - radius - 8,
                        (radius + 8) * 2, (radius + 8) * 2);
            }

            g.setColor(node.isPowered() ? POWERED_COLOR : FAILED_COLOR);
            g.fillOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2));
            g.drawOval(point.x - radius, point.y - radius, radius * 2, radius * 2);
            if (node instanceof Hospital) {
                drawHospitalMark(g, point);
            } else if (node instanceof EmergencyCentre) {
                drawEmergencyMark(g, point);
            }
            g.setFont(getFont().deriveFont(Font.BOLD, 13f));
            int idWidth = g.getFontMetrics().stringWidth(node.getId());
            g.drawString(node.getId(), point.x - idWidth / 2, point.y + 5);

            g.setColor(INK);
            g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
            String label = node.getName() + " | "
                    + (node.isPowered() ? "POWERED" : "AFFECTED");
            int labelWidth = g.getFontMetrics().stringWidth(label);
            g.drawString(label, point.x - labelWidth / 2, point.y + radius + 17);
        }
    }

    private void drawHospitalMark(Graphics2D g, Point point) {
        g.setColor(Color.WHITE);
        g.fillRect(point.x - 3, point.y - 11, 6, 22);
        g.fillRect(point.x - 11, point.y - 3, 22, 6);
    }

    private void drawEmergencyMark(Graphics2D g, Point point) {
        g.setColor(Color.WHITE);
        int[] xPoints = {point.x, point.x - 11, point.x + 11};
        int[] yPoints = {point.y - 11, point.y + 9, point.y + 9};
        g.fillPolygon(xPoints, yPoints, 3);
        g.setColor(POWERED_COLOR.darker());
        g.fillOval(point.x - 2, point.y + 2, 4, 4);
    }

    private void drawLegend(Graphics2D g) {
        int x = 14;
        int y = 14;
        String[] labels = {
            "Green Node = Powered",
            "Red Node = Affected",
            "Solid Line = Active",
            "Dashed Blue = Backup",
            "Red Line = Failed"
        };
        int width = 180;
        int height = 104;
        g.setColor(new Color(255, 255, 255, 235));
        g.fillRoundRect(x, y, width, height, 10, 10);
        g.setColor(new Color(207, 216, 225));
        g.drawRoundRect(x, y, width, height, 10, 10);
        g.setFont(getFont().deriveFont(Font.PLAIN, 12f));
        for (int i = 0; i < labels.length; i++) {
            int baseline = y + 20 + i * 17;
            if (i < 2) {
                g.setColor(i == 0 ? POWERED_COLOR : FAILED_COLOR);
                g.fillOval(x + 9, baseline - 10, 12, 12);
            } else {
                if (i == 2) {
                    g.setColor(POWERED_COLOR);
                    g.setStroke(new BasicStroke(3));
                } else if (i == 3) {
                    g.setColor(BACKUP_COLOR);
                    g.setStroke(new BasicStroke(3, BasicStroke.CAP_BUTT,
                            BasicStroke.JOIN_MITER, 10, new float[] {5, 4}, 0));
                } else {
                    g.setColor(FAILED_COLOR);
                    g.setStroke(new BasicStroke(3));
                }
                g.drawLine(x + 8, baseline - 4, x + 23, baseline - 4);
            }
            g.setColor(INK);
            g.drawString(labels[i], x + 30, baseline);
        }
    }

    private Point getNodeCoordinate(String nodeId) {
        for (Map.Entry<NetworkNode, Point> entry : nodeCoordinates.entrySet()) {
            if (entry.getKey().getId().equals(nodeId)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void assignDefaultCoordinates() {
        assign("S1", 95, 280);
        assign("F1", 360, 160);
        assign("F2", 360, 360);
        assign("C1", 650, 95);
        assign("C2", 930, 95);
        assign("C6", 650, 210);
        assign("C7", 930, 210);

        assign("H1", 650, 330);
        assign("C3", 930, 330);
        assign("C8", 650, 435);
        assign("C9", 930, 435);

        assign("S2", 95, 660);
        assign("F3", 360, 570);
        assign("F4", 360, 760);
        assign("C4", 650, 540);
        assign("E1", 930, 540);
        assign("C10", 650, 650);
        assign("E2", 930, 650);
        assign("C5", 650, 760);
        assign("C11", 930, 780);
    }

    private void assign(String nodeId, int x, int y) {
        NetworkNode node = network.getNode(nodeId);
        if (node != null) {
            nodeCoordinates.put(node, new Point(x, y));
        }
    }

    private static SensorMonitor createDefaultSensorMonitor(DistributionNetwork network) {
        SensorMonitor monitor = new SensorMonitor(20, 30, network);
        for (NetworkLine line : network.getLines()) {
            monitor.addReading(new SensorReading(line.getId(), 230, 10, 230, 10));
        }
        return monitor;
    }
}

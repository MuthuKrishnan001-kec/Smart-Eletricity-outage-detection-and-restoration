import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GridLayout;
import java.awt.Window;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.JButton;

public class EditStatusDialog extends JDialog {
    private final JComboBox<NetworkLine> lineSelector;
    private final JSpinner voltageSpinner;
    private final JSpinner repairTimeSpinner;
    private final JLabel repairTimeLabel = new JLabel("Estimated repair time (minutes):");
    private final JRadioButton workingButton = new JRadioButton("WORKING");
    private final JRadioButton failedButton = new JRadioButton("FAILED");
    private final JRadioButton standbyButton = new JRadioButton("STANDBY");
    private final DistributionNetwork network;
    private final SensorMonitor sensorMonitor;
    private final Consumer<NetworkLine> applyCallback;

    public EditStatusDialog(
            Window owner,
            DistributionNetwork network,
            NetworkNode node,
            List<NetworkLine> candidateLines,
            SensorMonitor sensorMonitor,
            Consumer<NetworkLine> applyCallback) {
        super(owner, "Edit Network Status", Dialog.ModalityType.APPLICATION_MODAL);
        if (network == null || candidateLines == null || candidateLines.isEmpty()
                || sensorMonitor == null) {
            throw new IllegalArgumentException(
                    "Network, sensor monitor, and at least one line are required.");
        }
        this.network = network;
        this.sensorMonitor = sensorMonitor;
        this.applyCallback = applyCallback == null ? line -> { } : applyCallback;
        lineSelector = new JComboBox<>(candidateLines.toArray(new NetworkLine[0]));
        voltageSpinner = new JSpinner(new SpinnerNumberModel(230.0, 0.0, 1000.0, 1.0));
        repairTimeSpinner = new JSpinner(new SpinnerNumberModel(
                Math.max(1, candidateLines.get(0).getRepairTimeMinutes()),
                1, Integer.MAX_VALUE, 1));
        buildInterface(node);
        loadSelectedLine();
        lineSelector.addActionListener(event -> loadSelectedLine());
        pack();
        setLocationRelativeTo(owner);
    }

    private void buildInterface(NetworkNode node) {
        JPanel fields = new JPanel(new GridLayout(0, 2, 8, 8));
        fields.add(new JLabel("Selected Line / Node ID:"));
        String selectedId = node == null
                ? ((NetworkLine) lineSelector.getSelectedItem()).getId()
                : node.getId();
        fields.add(new JLabel(selectedId));
        if (lineSelector.getItemCount() > 1) {
            fields.add(new JLabel("Incident line:"));
            fields.add(lineSelector);
        }
        fields.add(new JLabel("Voltage (V):"));
        fields.add(voltageSpinner);

        ButtonGroup statusGroup = new ButtonGroup();
        statusGroup.add(workingButton);
        statusGroup.add(failedButton);
        statusGroup.add(standbyButton);
        JPanel statusPanel = new JPanel();
        statusPanel.add(workingButton);
        statusPanel.add(failedButton);
        statusPanel.add(standbyButton);
        fields.add(new JLabel("Status:"));
        fields.add(statusPanel);
        fields.add(repairTimeLabel);
        fields.add(repairTimeSpinner);

        JButton applyButton = new JButton("Apply Changes");
        applyButton.addActionListener(event -> applyChanges());
        JPanel actions = new JPanel();
        actions.add(applyButton);

        failedButton.addActionListener(event -> updateRepairTimeAvailability());
        workingButton.addActionListener(event -> updateRepairTimeAvailability());
        standbyButton.addActionListener(event -> updateRepairTimeAvailability());

        setLayout(new BorderLayout(8, 8));
        add(fields, BorderLayout.CENTER);
        add(actions, BorderLayout.SOUTH);
    }

    private void loadSelectedLine() {
        NetworkLine line = (NetworkLine) lineSelector.getSelectedItem();
        if (line == null) {
            return;
        }
        SensorReading reading = sensorMonitor.getReading(line.getId());
        if (reading != null) {
            voltageSpinner.setValue(reading.getObservedVoltage());
        }
        repairTimeSpinner.setValue(line.getRepairTimeMinutes());
        workingButton.setSelected(line.getStatus() == LineStatus.WORKING);
        failedButton.setSelected(line.getStatus() == LineStatus.FAILED);
        standbyButton.setSelected(line.getStatus() == LineStatus.STANDBY);
        standbyButton.setEnabled(line.isBackupLink());
        if (!line.isBackupLink() && line.getStatus() == LineStatus.STANDBY) {
            workingButton.setSelected(true);
        }
        updateRepairTimeAvailability();
    }

    private void updateRepairTimeAvailability() {
        boolean enabled = failedButton.isSelected();
        repairTimeLabel.setEnabled(enabled);
        repairTimeSpinner.setEnabled(enabled);
    }

    private void applyChanges() {
        NetworkLine line = (NetworkLine) lineSelector.getSelectedItem();
        if (line == null) {
            JOptionPane.showMessageDialog(this, "Select a line to edit.",
                    "Unable to Apply", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (failedButton.isSelected()) {
            line.failLine(((Number) repairTimeSpinner.getValue()).intValue());
        } else if (standbyButton.isSelected()) {
            if (!line.isBackupLink()) {
                JOptionPane.showMessageDialog(this,
                        "Only backup lines can be placed in STANDBY.",
                        "Invalid Status", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (line.getStatus() == LineStatus.FAILED) {
                line.restoreLine();
            }
            line.deactivateBackup();
        } else {
            line.restoreLine();
            if (line.isBackupLink()) {
                line.activateBackup();
            }
        }

        double voltage = ((Number) voltageSpinner.getValue()).doubleValue();
        SensorReading previous = sensorMonitor.getReading(line.getId());
        if (previous != null) {
            sensorMonitor.addReading(new SensorReading(
                    line.getId(), previous.getNormalVoltage(), previous.getNormalCurrent(),
                    voltage, previous.getObservedCurrent()));
        }
        applyCallback.accept(line);
        setVisible(false);
        dispose();
    }
}

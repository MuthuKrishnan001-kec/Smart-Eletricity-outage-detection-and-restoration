const API_BASE = 'http://localhost:8081/api';
let currentNetworkState = null;
let currentEditNodeId = null;

function requestJson(path, options) {
  return fetch(`${API_BASE}${path}`, options).then(async (response) => {
    const body = await response.text();
    if (!response.ok) {
      let message = body || `Request failed with status ${response.status}`;
      try {
        const error = JSON.parse(body);
        message = error.error || message;
      } catch (parseError) {
        // Preserve a plain-text server error.
      }
      throw new Error(message);
    }
    return body ? JSON.parse(body) : {};
  });
}

function fetchOutages() {
  return fetchOutageQueue();
}

function fetchNetworkState() {
  return requestJson('/network').then((payload) => {
    currentNetworkState = payload;
    renderGridStatus(payload);
    renderNetworkLines(payload);
    const canvas = document.getElementById('networkCanvas');
    if (!canvas) {
      return payload;
    }
    drawNetwork(canvas, payload);
    return payload;
  }).catch((error) => {
    setGridStatus('Status unavailable', 'is-loading');
    throw error;
  });
}

function renderGridStatus(payload) {
  const nodes = Array.isArray(payload.nodes) ? payload.nodes : [];
  const lines = Array.isArray(payload.lines) ? payload.lines : [];
  const criticalFacilities = nodes.filter((node) =>
    node.nodeType === 'Hospital' || node.nodeType === 'EmergencyCentre');
  const failedLines = lines.filter((line) => line.status === 'FAILED');
  const affectedCriticalFacilities = criticalFacilities.filter((facility) =>
    !facility.powered);

  if (payload.summary && payload.summary.networkRestored === true) {
    setGridStatus('Network Restored', 'is-stable');
  } else if (affectedCriticalFacilities.length > 0) {
    setGridStatus('Critical Impact', 'is-critical');
  } else if (failedLines.length > 0 || nodes.some((node) => !node.powered)) {
    setGridStatus('Restoration Incomplete', 'is-outage');
  } else {
    setGridStatus('Grid Stable', 'is-stable');
  }
}

function setGridStatus(message, stateClass) {
  const badge = document.querySelector('[data-grid-status]');
  if (!badge) {
    return;
  }
  badge.textContent = message;
  badge.classList.remove('is-loading', 'is-stable', 'is-outage', 'is-critical');
  badge.classList.add(stateClass);
}

function fetchOutageQueue() {
  return requestJson('/outages').then((payload) => {
    renderOutages(payload);
    return payload;
  });
}

function fetchRepairTeams() {
  return requestJson('/teams').then((payload) => {
    renderRepairTeams(payload);
    return payload;
  });
}

function fetchHistory() {
  return requestJson('/history').then((payload) => {
    renderHistory(payload);
    return payload;
  });
}

function fetchBackupApprovals() {
  return requestJson('/backup-approvals').then((payload) => {
    renderBackupApprovals(payload);
    return payload;
  });
}

function drawNetwork(canvas, payload) {
  const context = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  context.clearRect(0, 0, width, height);

  const nodes = payload.nodes || [];
  const points = getNetworkPoints(payload, width, height);

  const lines = payload.lines || [];
  for (const [index, line] of lines.entries()) {
    const from = points.get(line.from);
    const to = points.get(line.to);
    if (!from || !to) {
      continue;
    }
    const failed = line.status === 'FAILED';
    const standby = !failed && line.status === 'STANDBY';
    context.beginPath();
    context.moveTo(from.x, from.y);
    context.lineTo(to.x, to.y);
    context.lineWidth = standby ? 3 : 4;
    context.strokeStyle = failed ? '#dc3d4b' : standby ? '#2864d7' : '#23945e';
    context.setLineDash(standby ? [9, 7] : []);
    context.stroke();

    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const length = Math.hypot(dx, dy) || 1;
    const offset = ((index % 3) - 1) * 9;
    const labelX = (from.x + to.x) / 2 - (dy / length) * offset;
    const labelY = (from.y + to.y) / 2 + (dx / length) * offset - 4;
    context.setLineDash([]);
    context.font = 'bold 11px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.lineWidth = 3;
    context.strokeStyle = '#f8fbff';
    context.strokeText(line.id, labelX, labelY);
    context.fillStyle = '#304257';
    context.fillText(line.id, labelX, labelY);
  }
  context.setLineDash([]);

  for (const node of nodes) {
    const point = points.get(node.id);
    const powered = Boolean(node.powered);
    const voltageAlert = Boolean(node.voltageAlert);
    context.beginPath();
    context.arc(point.x, point.y, node.nodeType === 'Substation' ? 22 : 17, 0, Math.PI * 2);
    context.fillStyle = !powered ? '#dc3d4b' : voltageAlert ? '#d9931a' : '#34a66a';
    context.fill();
    context.lineWidth = 2;
    context.strokeStyle = !powered ? '#a92635' : voltageAlert ? '#9d6810' : '#177344';
    context.stroke();

    context.fillStyle = '#fff';
    context.font = 'bold 12px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText(node.id, point.x, point.y);

    const labelAlignment = point.x > width - 150
      ? 'right'
      : point.x < 150
        ? 'left'
        : 'center';
    const labelX = labelAlignment === 'right'
      ? point.x - 23
      : labelAlignment === 'left'
        ? point.x + 23
        : point.x;
    context.fillStyle = '#455468';
    context.font = '12px sans-serif';
    context.textAlign = labelAlignment;
    context.textBaseline = 'top';
    context.fillText(node.name || node.id, labelX, point.y + 21, 220);
    context.fillText(powered ? 'POWERED' : 'AFFECTED', labelX, point.y + 36, 220);
    if (node.voltageEdited) {
      context.fillStyle = voltageAlert ? '#9d6810' : '#455468';
      context.fillText(
        `${voltageAlert ? 'LOW VOLTAGE · ' : ''}${node.observedVoltage} V`,
        labelX,
        point.y + 51,
        220);
    }
  }

  drawNetworkLegend(context);
}

function getNetworkPoints(payload, width, height) {
  const points = new Map();
  const nodes = payload.nodes || [];
  const occupiedPoints = new Set();
  for (const [index, node] of nodes.entries()) {
    let x = Number(node.x);
    let y = Number(node.y);
    const key = `${x},${y}`;
    if (!Number.isFinite(x) || !Number.isFinite(y) || occupiedPoints.has(key)) {
      const angle = (2 * Math.PI * index) / Math.max(nodes.length, 1);
      x = width / 2 + Math.cos(angle) * width * 0.38;
      y = height / 2 + Math.sin(angle) * height * 0.38;
    }
    occupiedPoints.add(`${x},${y}`);
    points.set(node.id, { x, y });
  }
  return points;
}

function drawNetworkLegend(context) {
  const items = [
    { color: '#34a66a', label: 'Green Node = Powered', type: 'node' },
    { color: '#d9931a', label: 'Amber Node = Low Voltage', type: 'node' },
    { color: '#dc3d4b', label: 'Red Node = Affected', type: 'node' },
    { color: '#23945e', label: 'Solid Line = Active', type: 'line' },
    { color: '#2864d7', label: 'Dashed Blue = Backup', type: 'dashed' },
    { color: '#dc3d4b', label: 'Red Line = Failed', type: 'line' }
  ];
  context.fillStyle = 'rgba(255, 255, 255, 0.96)';
  context.strokeStyle = '#cbd6e2';
  context.lineWidth = 1;
  context.beginPath();
  context.roundRect(12, 12, 205, 129, 8);
  context.fill();
  context.stroke();

  for (const [index, item] of items.entries()) {
    const y = 31 + index * 19;
    context.strokeStyle = item.color;
    context.fillStyle = item.color;
    context.lineWidth = 3;
    context.setLineDash(item.type === 'dashed' ? [5, 3] : []);
    context.beginPath();
    if (item.type === 'node') {
      context.arc(27, y, 5, 0, Math.PI * 2);
      context.fill();
    } else {
      context.moveTo(19, y);
      context.lineTo(36, y);
      context.stroke();
    }
    context.setLineDash([]);
    context.fillStyle = '#394b60';
    context.font = '12px sans-serif';
    context.textAlign = 'left';
    context.textBaseline = 'middle';
    context.fillText(item.label, 43, y);
  }
}

function findCanvasSelection(canvas, event) {
  if (!currentNetworkState) {
    return null;
  }
  const bounds = canvas.getBoundingClientRect();
  const click = {
    x: (event.clientX - bounds.left) * (canvas.width / bounds.width),
    y: (event.clientY - bounds.top) * (canvas.height / bounds.height)
  };
  const points = getNetworkPoints(currentNetworkState, canvas.width, canvas.height);
  let closestNode = null;
  let closestNodeDistance = 15;
  for (const node of currentNetworkState.nodes || []) {
    const point = points.get(node.id);
    const distance = Math.hypot(point.x - click.x, point.y - click.y);
    if (distance <= closestNodeDistance) {
      closestNode = node;
      closestNodeDistance = distance;
    }
  }
  if (closestNode) {
    const incidentLines = (currentNetworkState.lines || [])
      .filter((line) => line.from === closestNode.id || line.to === closestNode.id);
    return incidentLines.length
      ? { nodeId: closestNode.id, lines: incidentLines }
      : null;
  }

  let closestLine = null;
  let closestLineDistance = 15;
  for (const line of currentNetworkState.lines || []) {
    const from = points.get(line.from);
    const to = points.get(line.to);
    if (!from || !to) {
      continue;
    }
    const distance = pointToSegmentDistance(click, from, to);
    if (distance <= closestLineDistance) {
      closestLine = line;
      closestLineDistance = distance;
    }
  }
  return closestLine ? { lines: [closestLine] } : null;
}

function pointToSegmentDistance(point, from, to) {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  const lengthSquared = dx * dx + dy * dy;
  if (lengthSquared === 0) {
    return Math.hypot(point.x - from.x, point.y - from.y);
  }
  const projection = Math.max(0, Math.min(1,
    ((point.x - from.x) * dx + (point.y - from.y) * dy) / lengthSquared));
  return Math.hypot(
    point.x - (from.x + projection * dx),
    point.y - (from.y + projection * dy));
}

function setEditLine(lineId) {
  const line = (currentNetworkState?.lines || []).find((candidate) =>
    candidate.id === lineId);
  if (!line) {
    return;
  }
  const statusSelect = document.getElementById('editStatus');
  const standbyOption = statusSelect.querySelector('option[value="STANDBY"]');
  standbyOption.disabled = !line.backup;
  statusSelect.value = line.status === 'STANDBY' && !line.backup
    ? 'WORKING'
    : line.status;
  document.getElementById('editVoltage').value = line.observedVoltage ?? 230;
  document.getElementById('repairTimeMinutes').value = line.repairTimeMinutes ?? '';
  updateRepairTimeField();
}

function updateRepairTimeField() {
  const failed = !currentEditNodeId
    && document.getElementById('editStatus').value === 'FAILED';
  const field = document.getElementById('repairTimeField');
  const input = document.getElementById('repairTimeMinutes');
  field.hidden = !failed;
  input.required = failed;
}

function openEditModal(selection) {
  const modal = document.getElementById('editModal');
  const lineSelect = document.getElementById('editLineId');
  const lineField = document.getElementById('incidentLineField');
  const statusField = document.getElementById('editStatusField');
  const nodeId = selection.nodeId;
  currentEditNodeId = nodeId || null;
  document.getElementById('editSelectionTitle').textContent = nodeId
    ? `Selected Node ID: ${nodeId} · voltage change affects this node only`
    : `Selected Line ID: ${selection.lines[0].id}`;
  document.getElementById('editModalTitle').textContent = nodeId
    ? `Edit Node Voltage — ${nodeId}`
    : `Edit Network Status — ${selection.lines[0].id}`;

  lineSelect.replaceChildren();
  for (const line of selection.lines) {
    const option = document.createElement('option');
    option.value = line.id;
    option.textContent = line.id;
    lineSelect.appendChild(option);
  }
  lineField.hidden = Boolean(nodeId) || selection.lines.length < 2;
  statusField.hidden = Boolean(nodeId);
  lineSelect.value = selection.lines[0].id;
  setEditLine(lineSelect.value);
  if (nodeId) {
    const node = (currentNetworkState?.nodes || []).find((item) => item.id === nodeId);
    document.getElementById('editVoltage').value = node?.observedVoltage ?? 230;
  }
  document.getElementById('editError').textContent = '';
  modal.hidden = false;
  document.getElementById('editVoltage').focus();
}

function closeEditModal() {
  const modal = document.getElementById('editModal');
  if (modal) {
    modal.hidden = true;
  }
}

function applyManualEdit(event) {
  event.preventDefault();
  const lineId = document.getElementById('editLineId').value;
  const voltage = Number(document.getElementById('editVoltage').value);
  const status = document.getElementById('editStatus').value;
  const repairTimeText = document.getElementById('repairTimeMinutes').value.trim();
  const error = document.getElementById('editError');
  const applyButton = document.getElementById('applyEditButton');
  if (!Number.isFinite(voltage) || voltage < 0 || voltage > 1000) {
    error.textContent = 'Voltage must be between 0 and 1000 volts.';
    return;
  }
  let repairTimeMinutes;
  if (!currentEditNodeId && status === 'FAILED') {
    if (!/^[0-9]+$/.test(repairTimeText)
        || !Number.isSafeInteger(Number(repairTimeText))
        || Number(repairTimeText) < 1
        || Number(repairTimeText) > 2147483647) {
      error.textContent = 'Repair time must be a positive whole number of minutes.';
      return;
    }
    repairTimeMinutes = Number(repairTimeText);
  }
  error.textContent = '';
  applyButton.disabled = true;
  const requestPath = currentEditNodeId ? '/node-voltage' : '/action';
  const requestBody = currentEditNodeId
    ? { nodeId: currentEditNodeId, newVoltage: voltage }
    : {
      lineId,
      newVoltage: voltage,
      newStatus: status,
      ...(status === 'FAILED' ? { repairTimeMinutes } : {})
    };
  requestJson(requestPath, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(requestBody)
  }).then(() => {
    closeEditModal();
    return Promise.all([
      fetchNetworkState(),
      fetchOutages(),
      fetchRepairTeams(),
      fetchHistory()
    ]);
  }).then(() => {
    updateStatus('Manual update applied; live data refreshed.');
  }).catch((requestError) => {
    error.textContent = requestError.message;
    updateStatus(`Manual update failed: ${requestError.message}`);
  }).finally(() => {
    applyButton.disabled = false;
  });
}

function initializeCanvasEditing() {
  const canvas = document.getElementById('networkCanvas');
  const modal = document.getElementById('editModal');
  if (!canvas || !modal) {
    return;
  }
  canvas.addEventListener('click', (event) => {
    const selection = findCanvasSelection(canvas, event);
    if (selection) {
      openEditModal(selection);
    }
  });
  document.getElementById('editLineId').addEventListener('change', (event) => {
    setEditLine(event.target.value);
  });
  document.getElementById('editStatus').addEventListener('change', updateRepairTimeField);
  document.getElementById('editForm').addEventListener('submit', applyManualEdit);
  modal.querySelectorAll('[data-modal-cancel]').forEach((button) => {
    button.addEventListener('click', closeEditModal);
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !modal.hidden) {
      closeEditModal();
    }
  });
}

function appendCell(row, value) {
  const cell = document.createElement('td');
  cell.textContent = value == null || value === '' ? '—' : String(value);
  row.appendChild(cell);
}

function formatAffectedNodes(nodes) {
  return (nodes || []).map((node) =>
    typeof node === 'string' ? node : `${node.id} (${node.name})`).join(', ');
}

function renderNetworkLines(payload) {
  const body = document.querySelector('#networkLinesTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  for (const line of payload.lines || []) {
    const row = document.createElement('tr');
    appendCell(row, line.id);
    appendCell(row, line.status);
    appendCell(row, line.repairStatus);
    appendCell(row, line.approvalStatus);
    appendCell(row, formatAffectedNodes(line.affectedConsumers));
    appendCell(row, formatAffectedNodes(line.criticalFacilities));
    appendCell(row, line.assignedTeam);
    appendCell(row, `${line.repairTimeMinutes ?? 0} min`);
    appendCell(row, `${line.travelTimeMinutes ?? 0} min`);
    appendCell(row, `${line.estimatedRestorationTimeMinutes ?? 0} min`);
    appendCell(row, line.affectedConsumersRestored ? 'YES' : 'NO');
    appendCell(row, line.networkRestored ? 'YES' : 'NO');
    body.appendChild(row);
  }
}

function renderOutages(payload) {
  const body = document.querySelector('#outagesTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  for (const outage of payload.outages || []) {
    const row = document.createElement('tr');
    appendCell(row, outage.faultId);
    appendCell(row, outage.lineStatus);
    appendCell(row, outage.lineRepairStatus);
    appendPriorityCell(row, outage.priorityScore);
    appendCell(row, formatAffectedNodes(outage.affectedConsumers));
    appendCell(row, formatAffectedNodes(outage.criticalFacilities));
    appendCell(row, outage.assignedTeam
      ? `${outage.assignedTeam.id} • ${outage.assignedTeam.name}`
      : 'Unassigned');
    appendCell(row, (outage.travelTimeMinutes ?? 0) + ' min');
    appendCell(row, (outage.repairTimeMinutes ?? 0) + ' min');
    appendCell(row, (outage.estimatedRestorationTimeMinutes
      ?? outage.estimatedRepairMinutes ?? 0) + ' min');
    body.appendChild(row);
  }
  renderRestorationReports(payload.restorationReports || []);
}

function renderRestorationReports(reports) {
  const body = document.querySelector('#restorationTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  if (reports.length === 0) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 11;
    cell.textContent = 'No fault reports have been recorded.';
    row.appendChild(cell);
    body.appendChild(row);
    return;
  }
  for (const report of reports) {
    const row = document.createElement('tr');
    appendCell(row, report.lineId);
    appendCell(row, report.lineStatus);
    appendCell(row, report.lineRepairCompleted ? 'LINE REPAIRED' : 'REPAIR PENDING');
    appendCell(row, formatAffectedNodes(report.affectedConsumers));
    appendCell(row, formatAffectedNodes(report.criticalFacilities));
    appendCell(row, report.affectedConsumersRestored ? 'YES' : 'NO');
    appendCell(row, report.assignedTeam
      ? `${report.assignedTeam.id} - ${report.assignedTeam.name}` : 'Unassigned');
    appendCell(row, `${report.repairTimeMinutes ?? 0} min`);
    appendCell(row, `${report.travelTimeMinutes ?? 0} min`);
    appendCell(row, `${report.estimatedRestorationTimeMinutes ?? 0} min`);
    appendCell(row, report.networkFullyRestored ? 'YES' : 'NO');
    body.appendChild(row);
  }
}

function appendPriorityCell(row, priorityScore) {
  const cell = document.createElement('td');
  const score = document.createElement('span');
  score.className = 'priority-score';
  score.textContent = Number.isFinite(priorityScore)
    ? `${priorityScore} pts`
    : '—';
  cell.appendChild(score);
  row.appendChild(cell);
}

function renderRepairTeams(payload) {
  const body = document.querySelector('#teamsTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  for (const team of payload.teams || []) {
    const row = document.createElement('tr');
    appendCell(row, team.id);
    appendCell(row, team.name);
    appendCell(row, team.depot);
    appendCell(row, team.status);
    appendCell(row, team.assignedFault);
    appendCell(row, team.repairTimeMinutes == null ? '—' : `${team.repairTimeMinutes} min`);
    appendCell(row, team.travelTimeMinutes == null ? '—' : `${team.travelTimeMinutes} min`);
    appendCell(row, team.estimatedRestorationTimeMinutes == null
      ? '—' : `${team.estimatedRestorationTimeMinutes} min`);
    body.appendChild(row);
  }
}

function renderHistory(payload) {
  const body = document.querySelector('#historyTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  for (const event of payload.history || []) {
    const row = document.createElement('tr');
    appendCell(row, event.timestamp);
    appendCell(row, event.eventType);
    appendCell(row, event.lineId);
    appendCell(row, event.affectedZones);
    appendCell(row, event.assignedTeam);
    appendCell(row, event.status);
    body.appendChild(row);
  }
}

function renderBackupApprovals(payload) {
  const body = document.querySelector('#backupApprovalsTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  const proposals = payload.proposals || [];
  if (proposals.length === 0) {
    const row = document.createElement('tr');
    const cell = document.createElement('td');
    cell.colSpan = 6;
    cell.textContent = 'No backup link proposals.';
    row.appendChild(cell);
    body.appendChild(row);
    return;
  }

  for (const proposal of proposals) {
    const row = document.createElement('tr');
    appendCell(row, proposal.lineId);
    appendCell(row, proposal.currentStatus);
    appendCell(row, (proposal.path || []).join(' → '));
    appendCell(row, proposal.approvalStatus);
    appendCell(row, (proposal.affectedConsumers || []).map((consumer) =>
      `${consumer.id} (${consumer.name})`).join(', '));
    const actions = document.createElement('td');
    if (proposal.approvalStatus === 'PROPOSED') {
      actions.appendChild(createBackupActionButton(proposal.lineId, 'approve', 'Approve'));
      actions.appendChild(createBackupActionButton(proposal.lineId, 'reject', 'Reject'));
    } else if (proposal.approvalStatus === 'APPROVED') {
      actions.appendChild(createBackupActionButton(proposal.lineId, 'activate', 'Activate'));
      actions.appendChild(createBackupActionButton(proposal.lineId, 'reject', 'Reject'));
    } else {
      actions.textContent = '—';
    }
    row.appendChild(actions);
    body.appendChild(row);
  }
}

function createBackupActionButton(lineId, action, label) {
  const button = document.createElement('button');
  button.type = 'button';
  button.className = 'header-action backup-action-button';
  button.textContent = label;
  button.addEventListener('click', () => {
    button.disabled = true;
    postAction(`/actions/backup-${action}`, { lineId })
      .then(() => updateStatus(`${lineId} ${action} action recorded.`))
      .catch((error) => {
        console.error(`Backup link ${action} failed:`, error);
        updateStatus(`Backup link ${action} failed: ${error.message}`);
      });
  });
  return button;
}

function refreshLiveData() {
  return Promise.all([
    fetchNetworkState(),
    fetchOutageQueue(),
    fetchRepairTeams(),
    fetchHistory(),
    fetchBackupApprovals()
  ]);
}

function updateStatus(message) {
  for (const status of document.querySelectorAll('[data-api-status]')) {
    status.textContent = message;
  }
}

function postAction(path, body) {
  return requestJson(path, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body || {})
  }).then((result) => {
    return Promise.all([
      fetchNetworkState(),
      fetchOutageQueue(),
      fetchRepairTeams(),
      fetchHistory(),
      fetchBackupApprovals()
    ]).then(() => result);
  });
}

function autoDetect() {
  const eventTypes = ['Storm', 'Peak Load Overload', 'Random Aging Fault'];
  const eventType = eventTypes[Math.floor(Math.random() * eventTypes.length)];
  console.log('Auto Detect selected event:', eventType);
  return postAction('/actions/simulate-fault', { eventType });
}

function runFrontendAction(action) {
  const operations = {
    autoDetect,
    restore: () => postAction('/actions/auto-restore'),
    reset: () => postAction('/actions/reset')
  };
  const operation = operations[action];
  if (!operation) {
    return;
  }
  updateStatus('Working…');
  operation()
    .then(() => updateStatus('Live data refreshed.'))
    .catch((error) => {
      console.error('Grid operation failed:', error);
      updateStatus(`Operation failed: ${error.message}`);
    });
}

function initializeLivePages() {
  initializeCanvasEditing();
  document.querySelectorAll('[data-grid-action]').forEach((button) => {
    button.addEventListener('click', () => {
      runFrontendAction(button.dataset.gridAction);
    });
  });
  refreshLiveData()
    .then(() => updateStatus('Connected to live Java API.'))
    .catch((error) => {
      console.error('Unable to load live grid data:', error);
      updateStatus(`Unable to load live data: ${error.message}`);
    });
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeLivePages);
} else {
  initializeLivePages();
}

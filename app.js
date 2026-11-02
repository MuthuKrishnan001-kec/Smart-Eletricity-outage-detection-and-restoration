const API_BASE = String(
  window.SMART_GRID_API_BASE || 'http://localhost:8081/api'
).replace(/\/+$/, '');
const REQUEST_TIMEOUT_MS = 10000;
const LIVE_REFRESH_INTERVAL_MS = 5000;
let currentNetworkState = null;
let currentEditNodeId = null;
let manualEditPending = false;
let manualEditController = null;
let currentGraphSelection = null;
let graphTransform = { scale: 1, x: 0, y: 0 };
let graphPointer = null;
let suppressGraphClick = false;
let automaticRefreshInProgress = false;
const dashboardData = {
  network: null,
  outages: null,
  teams: null,
  history: null
};
const dashboardDataErrors = {
  network: null,
  outages: null,
  teams: null,
  history: null
};

function createRequestError(message, reconcileRequired = false) {
  const error = new Error(message);
  error.reconcileRequired = reconcileRequired;
  return error;
}

function requestJson(path, options = {}) {
  const controller = new AbortController();
  const externalSignal = options.signal;
  let timedOut = false;
  let abortFromExternal;
  const timeoutId = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, REQUEST_TIMEOUT_MS);
  if (externalSignal) {
    abortFromExternal = () => controller.abort();
    if (externalSignal.aborted) {
      abortFromExternal();
    } else {
      externalSignal.addEventListener('abort', abortFromExternal, { once: true });
    }
  }

  return fetch(`${API_BASE}${path}`, {
    ...options,
    cache: options.cache || 'no-store',
    signal: controller.signal
  }).then(async (response) => {
    const body = await response.text();
    if (!response.ok) {
      const diagnostic = body.trim().slice(0, 240);
      throw createRequestError(
        `HTTP ${response.status} for ${path}.${diagnostic ? ` Server response: ${diagnostic}` : ''}`,
        response.status >= 500);
    }
    const contentType = response.headers.get('content-type') || 'not provided';
    if (!contentType.toLowerCase().includes('application/json')) {
      const diagnostic = body.trim().slice(0, 240);
      throw createRequestError(
        `Expected JSON from ${path}, but received ${contentType}.`
        + `${diagnostic ? ` Response starts with: ${diagnostic}` : ''}`,
        true);
    }

    let payload;
    try {
      payload = JSON.parse(body);
    } catch (parseError) {
      const diagnostic = body.trim().slice(0, 240) || '(empty response body)';
      throw createRequestError(
        `Invalid JSON from ${path} (HTTP ${response.status}): ${parseError.message}. `
        + `Response starts with: ${diagnostic}`,
        true);
    }
    if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
      throw createRequestError(
        `Invalid response from ${path}: expected a JSON object.`,
        true);
    }
    return payload;
  }).catch((requestError) => {
    if (timedOut) {
      throw createRequestError(
        `Request timed out after ${REQUEST_TIMEOUT_MS / 1000} seconds.`, true);
    }
    if (externalSignal?.aborted || requestError.name === 'AbortError') {
      throw createRequestError(
        'Request cancelled. The server may still finish an update it already received.', true);
    }
    if (requestError instanceof TypeError) {
      throw createRequestError(
        `Network request failed. Check that the backend is running. ${requestError.message}`);
    }
    throw requestError;
  }).finally(() => {
    clearTimeout(timeoutId);
    if (externalSignal && abortFromExternal) {
      externalSignal.removeEventListener('abort', abortFromExternal);
    }
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
    dashboardData.network = payload;
    dashboardDataErrors.network = null;
    const canvas = document.getElementById('networkCanvas');
    if (!canvas) {
      renderDashboardOverview();
      return payload;
    }
    drawNetwork(canvas, payload);
    setGraphStatus(
      Array.isArray(payload.nodes) && payload.nodes.length
        ? ''
        : 'No network data was returned by the backend.');
    renderDashboardOverview();
    if (currentGraphSelection) {
      renderDashboardDetails(currentGraphSelection);
    }
    return payload;
  }).catch((error) => {
    dashboardData.network = null;
    dashboardDataErrors.network = error;
    currentNetworkState = null;
    setGridStatus('Status unavailable', 'is-loading');
    setGraphStatus(`Network graph unavailable: ${error.message}`);
    const canvas = document.getElementById('networkCanvas');
    if (canvas) {
      canvas.getContext('2d').clearRect(0, 0, canvas.width, canvas.height);
    }
    const details = document.getElementById('networkDetails');
    if (details) {
      details.querySelector('[data-details-empty]').textContent =
        'Node and line details are unavailable until the backend reconnects.';
      details.querySelector('[data-details-empty]').hidden = false;
      details.querySelector('[data-node-details]').hidden = true;
      details.querySelector('[data-selection-edit]').hidden = true;
    }
    currentGraphSelection = null;
    renderDashboardOverview();
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
    if (!Array.isArray(payload.outages)
        || !Array.isArray(payload.restorationReports)) {
      throw createRequestError(
        'Invalid response from /outages: expected outages and restorationReports arrays.',
        true);
    }
    dashboardData.outages = payload;
    dashboardDataErrors.outages = null;
    renderOutages(payload);
    renderDashboardOverview();
    if (currentGraphSelection) {
      renderDashboardDetails(currentGraphSelection);
    }
    return payload;
  }).catch((error) => {
    dashboardData.outages = null;
    dashboardDataErrors.outages = error;
    const outagesBody = document.querySelector('#outagesTable tbody');
    if (outagesBody) {
      renderEmptyTableRow(
        outagesBody,
        10,
        `Outage data unavailable: ${error.message}`);
    }
    const restorationBody = document.querySelector('#restorationTable tbody');
    if (restorationBody) {
      renderEmptyTableRow(
        restorationBody,
        11,
        `Restoration data unavailable: ${error.message}`);
    }
    renderDashboardOverview();
    if (currentGraphSelection) {
      renderDashboardDetails(currentGraphSelection);
    }
    throw error;
  });
}

function fetchRepairTeams() {
  return requestJson('/teams').then((payload) => {
    dashboardData.teams = payload;
    dashboardDataErrors.teams = null;
    renderRepairTeams(payload);
    renderDashboardOverview();
    return payload;
  }).catch((error) => {
    dashboardData.teams = null;
    dashboardDataErrors.teams = error;
    renderDashboardOverview();
    throw error;
  });
}

function fetchHistory() {
  return requestJson('/history').then((payload) => {
    if (!Array.isArray(payload.history)) {
      throw createRequestError(
        'Invalid response from /history: expected a history array.',
        true);
    }
    dashboardData.history = payload;
    dashboardDataErrors.history = null;
    renderHistory(payload);
    renderDashboardOverview();
    return payload;
  }).catch((error) => {
    dashboardData.history = null;
    dashboardDataErrors.history = error;
    const summary = document.getElementById('historySummary');
    if (summary) {
      summary.textContent = `Daily fault count unavailable: ${error.message}`;
    }
    const updated = document.getElementById('historyLastUpdated');
    if (updated) {
      updated.textContent = 'History could not be refreshed';
    }
    const historyBody = document.querySelector('#historyTable tbody');
    if (historyBody) {
      renderEmptyTableRow(
        historyBody,
        6,
        `Outage history unavailable: ${error.message}`);
    }
    renderDashboardOverview();
    throw error;
  });
}

function fetchBackupApprovals() {
  return requestJson('/backup-approvals').then((payload) => {
    renderBackupApprovals(payload);
    return payload;
  }).catch((error) => {
    const body = document.querySelector('#backupApprovalsTable tbody');
    if (document.body.classList.contains('dashboard-page') && body) {
      renderEmptyTableRow(
        body,
        6,
        `Backup approvals unavailable: ${error.message}`);
    }
    throw error;
  });
}

function drawNetwork(canvas, payload) {
  const context = canvas.getContext('2d');
  const width = canvas.width;
  const height = canvas.height;
  const lightTheme = document.body.classList.contains('dashboard-page');
  context.clearRect(0, 0, width, height);

  const nodes = payload.nodes || [];
  const points = transformNetworkPoints(
    getNetworkPoints(payload, width, height), width, height);

  const lines = payload.lines || [];
  for (const [index, line] of lines.entries()) {
    const from = points.get(line.from);
    const to = points.get(line.to);
    if (!from || !to) {
      continue;
    }
    const color = getLineColor(line);
    const selected = currentGraphSelection?.lineId === line.id;
    context.beginPath();
    context.moveTo(from.x, from.y);
    context.lineTo(to.x, to.y);
    context.lineWidth = selected ? 6 : 3;
    context.strokeStyle = color;
    context.setLineDash(line.approvalStatus === 'PROPOSED' ? [8, 7]
      : line.backup ? [8, 6] : []);
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
    context.lineWidth = 4;
    context.strokeStyle = lightTheme ? '#ffffff' : '#0e1929';
    context.strokeText(line.id, labelX, labelY);
    context.fillStyle = color;
    context.fillText(line.id, labelX, labelY);
  }
  context.setLineDash([]);

  for (const node of nodes) {
    const point = points.get(node.id);
    if (!point) {
      continue;
    }
    const powered = Boolean(node.powered);
    const voltageAlert = Boolean(node.voltageAlert);
    const radius = node.nodeType === 'Substation' ? 19 : 14;
    const selected = currentGraphSelection?.nodeId === node.id;
    const fillColor = !powered ? '#ef4444' : voltageAlert ? '#f59e0b' : '#22c55e';
    if (selected) {
      context.beginPath();
      context.arc(point.x, point.y, radius + 7, 0, Math.PI * 2);
      context.strokeStyle = lightTheme ? '#2563eb' : '#22d3ee';
      context.lineWidth = 2;
      context.stroke();
    }
    drawNetworkNodeShape(context, node, point, radius, fillColor, lightTheme);
    context.fillStyle = '#f1f5f9';
    context.font = 'bold 10px sans-serif';
    context.textAlign = 'center';
    context.textBaseline = 'middle';
    context.fillText(node.id, point.x, point.y);

    const labelAlignment = point.x > width - 150
      ? 'right'
      : point.x < 150
        ? 'left'
        : 'center';
    const labelX = labelAlignment === 'right'
      ? point.x - radius - 5
      : labelAlignment === 'left'
        ? point.x + radius + 5
        : point.x;
    context.fillStyle = lightTheme ? '#334155' : '#d7e3f2';
    context.font = '11px sans-serif';
    context.textAlign = labelAlignment;
    context.textBaseline = 'top';
    context.fillText(node.name || node.id, labelX, point.y + radius + 4, 190);
    context.fillStyle = lightTheme
      ? !powered ? '#b91c1c' : voltageAlert ? '#b45309' : '#15803d'
      : !powered ? '#fca5a5' : voltageAlert ? '#fcd34d' : '#86efac';
    context.font = 'bold 9px sans-serif';
    context.fillText(powered ? 'CONNECTED' : 'DISCONNECTED', labelX, point.y + radius + 18, 190);
    if (node.voltageEdited) {
      context.fillStyle = lightTheme
        ? voltageAlert ? '#b45309' : '#475569'
        : voltageAlert ? '#fcd34d' : '#a9bbd1';
      context.fillText(
        `${voltageAlert ? 'VOLTAGE ALERT · ' : ''}${node.observedVoltage} V`,
        labelX,
        point.y + radius + 31,
        190);
    }
  }

  drawNetworkLegend(context, lightTheme);
}

function drawNetworkNodeShape(context, node, point, radius, fillColor, lightTheme) {
  context.beginPath();
  if (node.nodeType === 'Substation') {
    context.rect(point.x - radius, point.y - radius, radius * 2, radius * 2);
  } else if (node.nodeType === 'Feeder') {
    context.moveTo(point.x, point.y - radius);
    context.lineTo(point.x + radius, point.y);
    context.lineTo(point.x, point.y + radius);
    context.lineTo(point.x - radius, point.y);
    context.closePath();
  } else if (node.nodeType === 'EmergencyCentre') {
    context.moveTo(point.x, point.y - radius);
    context.lineTo(point.x + radius, point.y + radius);
    context.lineTo(point.x - radius, point.y + radius);
    context.closePath();
  } else {
    context.arc(point.x, point.y, radius, 0, Math.PI * 2);
  }
  context.fillStyle = fillColor;
  context.fill();
  context.lineWidth = 2;
  context.strokeStyle = node.nodeType === 'Hospital' || node.nodeType === 'EmergencyCentre'
    ? lightTheme ? '#ffffff' : '#f1f5f9'
    : lightTheme ? '#ffffff' : '#0b1220';
  context.stroke();

  if (node.nodeType === 'Hospital') {
    context.beginPath();
    context.strokeStyle = lightTheme ? '#1e293b' : '#0b1220';
    context.lineWidth = 3;
    context.moveTo(point.x - 6, point.y);
    context.lineTo(point.x + 6, point.y);
    context.moveTo(point.x, point.y - 6);
    context.lineTo(point.x, point.y + 6);
    context.stroke();
  }
}

function getLineColor(line) {
  if (line.status === 'FAILED') {
    return '#ef4444';
  }
  if (line.backup && line.approvalStatus === 'PROPOSED') {
    return '#f59e0b';
  }
  if (line.backup
      && ['APPROVED', 'ACTIVE'].includes(line.approvalStatus)) {
    return '#3b82f6';
  }
  return '#22c55e';
}

function transformNetworkPoints(points, width, height) {
  const transformed = new Map();
  for (const [id, point] of points) {
    transformed.set(id, {
      x: (point.x - width / 2) * graphTransform.scale + width / 2 + graphTransform.x,
      y: (point.y - height / 2) * graphTransform.scale + height / 2 + graphTransform.y
    });
  }
  return transformed;
}

function getNetworkPoints(payload, width, height) {
  const points = new Map();
  const nodes = payload.nodes || [];
  const occupiedPoints = new Set();
  for (const [index, node] of nodes.entries()) {
    const sourceX = Number(node.x);
    const sourceY = Number(node.y);
    const key = `${sourceX},${sourceY}`;
    if (!Number.isFinite(sourceX) || !Number.isFinite(sourceY) || occupiedPoints.has(key)) {
      const angle = (2 * Math.PI * index) / Math.max(nodes.length, 1);
      const x = width / 2 + Math.cos(angle) * width * 0.38;
      const y = height / 2 + Math.sin(angle) * height * 0.38;
      occupiedPoints.add(`${x},${y}`);
      points.set(node.id, { x, y });
      continue;
    }
    const x = sourceX * (width / 1100);
    const y = sourceY * (height / 1000);
    occupiedPoints.add(key);
    points.set(node.id, { x, y });
  }
  return points;
}

function setGraphStatus(message) {
  const status = document.querySelector('[data-graph-status]');
  if (!status) {
    return;
  }
  status.textContent = message;
  status.hidden = !message;
}

function renderDashboardOverview() {
  if (!document.body.classList.contains('dashboard-page')) {
    return;
  }

  const network = dashboardData.network;
  const teams = dashboardData.teams;
  const outages = dashboardData.outages;
  const history = dashboardData.history;
  const summary = network?.summary;
  const lines = Array.isArray(network?.lines) ? network.lines : null;
  const setStat = (key, value, source) => {
    const element = document.querySelector(`[data-stat="${key}"]`);
    const foot = document.querySelector(`[data-stat-foot="${key}"]`);
    const footLabel = foot?.querySelector('.stat-foot-label') || foot;
    if (!element) {
      return;
    }
    const failed = dashboardDataErrors[source];
    if (value == null) {
      element.textContent = failed || dashboardData[source] ? 'Unavailable' : 'Loading…';
      if (footLabel) {
        footLabel.dataset.defaultText ||= footLabel.textContent;
        footLabel.textContent = failed || dashboardData[source]
          ? 'Backend data unavailable'
          : footLabel.dataset.defaultText;
      }
      return;
    }
    if (footLabel?.dataset.defaultText) {
      footLabel.textContent = footLabel.dataset.defaultText;
    }
    element.textContent = String(value);
  };

  setStat('nodes', Number.isFinite(summary?.totalNodes) ? summary.totalNodes : null, 'network');
  setStat('lines', Number.isFinite(summary?.totalLines) ? summary.totalLines : null, 'network');
  setStat('faults', Number.isFinite(summary?.failedLineCount)
    ? summary.failedLineCount : null, 'network');
  setStat('consumers', Number.isFinite(summary?.disconnectedConsumers)
    ? summary.disconnectedConsumers : null, 'network');
  setStat('teams', Array.isArray(teams?.teams)
    ? teams.teams.filter((team) => team.status === 'AVAILABLE').length : null, 'teams');

  let restoredConsumers = null;
  if (Array.isArray(outages?.restorationReports)) {
    const restoredIds = new Set();
    for (const report of outages.restorationReports) {
      if (report.affectedConsumersRestored && Array.isArray(report.affectedConsumers)) {
        for (const consumer of report.affectedConsumers) {
          if (consumer && consumer.id) {
            restoredIds.add(consumer.id);
          }
        }
      }
    }
    restoredConsumers = restoredIds.size;
  }
  setStat('restored', restoredConsumers, 'outages');

  const workingCount = lines?.filter((line) => line.status === 'WORKING').length;
  const failedCount = lines?.filter((line) => line.status === 'FAILED').length;
  setDashboardMetric('[data-health-working]', workingCount, dashboardDataErrors.network);
  setDashboardMetric('[data-health-failed]', failedCount, dashboardDataErrors.network);
  setDashboardMetric(
    '[data-health-outages]',
    Number.isFinite(summary?.failedLineCount) ? summary.failedLineCount : null,
    dashboardDataErrors.network);

  const criticalFacilities = Array.isArray(network?.nodes)
    ? network.nodes.filter((node) =>
      node.nodeType === 'Hospital' || node.nodeType === 'EmergencyCentre')
    : null;
  const criticalElement = document.querySelector('[data-health-critical]');
  if (criticalElement) {
    if (!criticalFacilities) {
      criticalElement.textContent = dashboardDataErrors.network || network
        ? 'Unavailable' : 'Loading…';
    } else {
      const disconnected = criticalFacilities.filter((facility) => !facility.powered).length;
      criticalElement.textContent = criticalFacilities.length === 0
        ? 'No facilities reported'
        : disconnected === 0
          ? `${criticalFacilities.length} connected`
          : `${disconnected} disconnected`;
    }
  }

  const healthSummary = document.querySelector('[data-health-summary]');
  if (healthSummary) {
    if (!summary || !Array.isArray(network?.nodes)) {
      healthSummary.textContent = dashboardDataErrors.network || network
        ? 'Connectivity unavailable' : 'Loading network status…';
    } else if (summary.networkRestored === true) {
      healthSummary.textContent = 'Graph connectivity restored';
    } else if (criticalFacilities?.some((facility) => !facility.powered)) {
      healthSummary.textContent = 'Critical facility disconnected';
    } else if ((failedCount || 0) > 0) {
      healthSummary.textContent = 'Restoration in progress';
    } else {
      healthSummary.textContent = 'Graph status stable';
    }
  }

  renderRecentActivity(history, outages);
  if (network && teams && outages && history
      && Object.values(dashboardDataErrors).every((error) => !error)) {
    const updated = document.querySelector('[data-last-updated]');
    if (updated) {
      updated.textContent = new Date().toLocaleTimeString();
    }
  }
}

function setDashboardMetric(selector, value, error) {
  const element = document.querySelector(selector);
  if (element) {
    element.textContent = value == null
      ? error || dashboardData.network ? 'Unavailable' : 'Loading…'
      : String(value);
  }
}

function renderRecentActivity(historyPayload, outagesPayload) {
  const body = document.querySelector('#recentActivityTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  if (!historyPayload) {
    renderEmptyTableRow(
      body,
      7,
      dashboardDataErrors.history
        ? `Outage history unavailable: ${dashboardDataErrors.history.message}`
        : 'Loading outage history…');
    return;
  }
  if (!Array.isArray(historyPayload.history)) {
    renderEmptyTableRow(body, 7, 'History records are unavailable in the backend response.');
    return;
  }
  const events = historyPayload.history.slice(-6).reverse();
  if (events.length === 0) {
    renderEmptyTableRow(body, 7, 'No outage activity is recorded.');
    return;
  }
  const outagesByLine = new Map(
    (outagesPayload?.outages || []).map((outage) => [outage.lineId, outage]));
  for (const event of events) {
    const row = document.createElement('tr');
    const outage = outagesByLine.get(event.lineId);
    appendCell(row, outage?.faultId);
    appendCell(row, event.lineId);
    appendCell(row, event.eventType);
    appendCell(row, event.affectedZones);
    appendCell(row, event.assignedTeam);
    appendCell(row, event.status);
    appendCell(row, event.timestamp);
    body.appendChild(row);
  }
}

function renderEmptyTableRow(body, columns, message) {
  const row = document.createElement('tr');
  const cell = document.createElement('td');
  cell.colSpan = columns;
  cell.textContent = message;
  body.replaceChildren(row);
  row.appendChild(cell);
}

function renderDashboardDetails(selection) {
  const panel = document.getElementById('networkDetails');
  if (!panel || !currentNetworkState) {
    return;
  }
  const node = selection.nodeId
    ? (currentNetworkState.nodes || []).find((item) => item.id === selection.nodeId)
    : null;
  const line = selection.lineId
    ? (currentNetworkState.lines || []).find((item) => item.id === selection.lineId)
    : null;
  if (!node && !line) {
    return;
  }
  const lines = node
    ? (currentNetworkState.lines || []).filter((item) =>
      item.from === node.id || item.to === node.id)
    : [line];
  const fields = {
    id: node ? node.id : line.id,
    name: node ? node.name : `${line.from} ↔ ${line.to}`,
    type: node ? node.nodeType : (line.backup ? 'Backup connection' : 'Distribution line'),
    voltage: node
      ? (Number.isFinite(node.observedVoltage) ? `${node.observedVoltage} V` : 'Not reported')
      : (Number.isFinite(line.observedVoltage) ? `${line.observedVoltage} V` : 'Not reported'),
    current: node
      ? 'Not reported for node'
      : (Number.isFinite(line.observedCurrent) ? `${line.observedCurrent} A` : 'Not reported'),
    status: node
      ? (node.powered ? 'Graph-connected' : 'Graph-disconnected')
      : `${line.status}${line.approvalStatus && line.approvalStatus !== 'NONE'
        ? ` · ${line.approvalStatus}` : ''}`,
    lines: lines.map((item) => node
      ? `${item.id} (${item.status}; ${Number.isFinite(item.observedCurrent)
        ? `${item.observedCurrent} A line reading` : 'current unavailable'})`
      : item.id).join(', ') || 'None reported'
  };
  const activeOutages = dashboardData.outages?.outages || [];
  const fault = activeOutages.find((outage) =>
    node
      ? (outage.affectedNodes || []).some((affected) => affected.id === node.id)
        || (outage.affectedConsumers || []).some((affected) => affected.id === node.id)
        || (outage.criticalFacilities || []).some((affected) => affected.id === node.id)
      : outage.lineId === line.id);
  fields.fault = fault
    ? `${fault.faultId} · ${fault.lineRepairStatus}`
    : dashboardDataErrors.outages
      ? 'Outage report unavailable'
      : 'No associated active fault report';

  for (const [key, value] of Object.entries(fields)) {
    const target = panel.querySelector(`[data-detail="${key}"]`);
    if (target) {
      target.textContent = value;
    }
  }
  panel.querySelector('[data-details-empty]').hidden = true;
  panel.querySelector('[data-node-details]').hidden = false;
  panel.querySelector('[data-selection-edit]').hidden = false;
  currentGraphSelection = selection;
  const canvas = document.getElementById('networkCanvas');
  if (canvas) {
    drawNetwork(canvas, currentNetworkState);
  }
}

function selectDashboardNetworkItem(selection) {
  const nextSelection = selection.nodeId
    ? { nodeId: selection.nodeId }
    : { lineId: selection.lines[0].id };
  currentGraphSelection = nextSelection;
  renderDashboardDetails(nextSelection);
}

function zoomNetworkCanvas(canvas, nextScale, anchor) {
  const boundedScale = Math.min(2.5, Math.max(0.65, nextScale));
  if (anchor) {
    const x = anchor.x - canvas.width / 2 - graphTransform.x;
    const y = anchor.y - canvas.height / 2 - graphTransform.y;
    const worldX = x / graphTransform.scale;
    const worldY = y / graphTransform.scale;
    graphTransform.x = anchor.x - canvas.width / 2 - worldX * boundedScale;
    graphTransform.y = anchor.y - canvas.height / 2 - worldY * boundedScale;
  }
  graphTransform.scale = boundedScale;
  if (currentNetworkState) {
    drawNetwork(canvas, currentNetworkState);
  }
}

function canvasPoint(canvas, event) {
  const bounds = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - bounds.left) * (canvas.width / bounds.width),
    y: (event.clientY - bounds.top) * (canvas.height / bounds.height)
  };
}

function drawNetworkLegend(context, lightTheme = false) {
  const items = [
    { color: '#22c55e', label: 'Green Node = Graph-connected', type: 'node' },
    { color: '#f59e0b', label: 'Amber Node = Voltage alert', type: 'node' },
    { color: '#ef4444', label: 'Red Node = Graph-disconnected', type: 'node' },
    { color: '#22c55e', label: 'Working connection', type: 'line' },
    { color: '#3b82f6', label: 'Approved backup', type: 'dashed' },
    { color: '#f59e0b', label: 'Pending backup', type: 'dashed' },
    { color: '#ef4444', label: 'Failed connection', type: 'line' }
  ];
  context.fillStyle = lightTheme ? 'rgba(255, 255, 255, 0.96)' : 'rgba(11, 18, 32, 0.94)';
  context.strokeStyle = lightTheme ? '#cbd5e1' : '#34465e';
  context.lineWidth = 1;
  context.beginPath();
  context.roundRect(12, 12, 226, 151, 8);
  context.fill();
  context.stroke();

  for (const [index, item] of items.entries()) {
    const y = 29 + index * 19;
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
    context.fillStyle = lightTheme ? '#334155' : '#cbd5e1';
    context.font = '11px sans-serif';
    context.textAlign = 'left';
    context.textBaseline = 'middle';
    context.fillText(item.label, 43, y);
  }
}

function findCanvasSelection(canvas, event) {
  if (!currentNetworkState) {
    return null;
  }
  const click = canvasPoint(canvas, event);
  const points = transformNetworkPoints(
    getNetworkPoints(currentNetworkState, canvas.width, canvas.height),
    canvas.width,
    canvas.height);
  let closestNode = null;
  let closestNodeDistance = 19 * graphTransform.scale;
  for (const node of currentNetworkState.nodes || []) {
    const point = points.get(node.id);
    if (!point) {
      continue;
    }
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
  let closestLineDistance = 13 * graphTransform.scale;
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
  return closestLine ? { lineId: closestLine.id, lines: [closestLine] } : null;
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
    return false;
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
  if (!currentEditNodeId) {
    document.getElementById('editSelectionTitle').textContent =
      `Selected Line ID: ${line.id}`;
    document.getElementById('editModalTitle').textContent =
      `Edit Network Status — ${line.id}`;
  }
  return true;
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

  const lines = currentNetworkState?.lines || [];
  const initialLineId = selection.lines[0]?.id;
  lineSelect.replaceChildren();
  for (const line of lines) {
    const option = document.createElement('option');
    option.value = line.id;
    option.textContent = line.id;
    lineSelect.appendChild(option);
  }
  lineField.hidden = Boolean(nodeId);
  statusField.hidden = Boolean(nodeId);
  if (initialLineId && lines.some((line) => line.id === initialLineId)) {
    lineSelect.value = initialLineId;
    setEditLine(lineSelect.value);
  }
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

async function applyManualEdit(event) {
  event.preventDefault();
  if (manualEditPending) {
    return;
  }
  const nodeId = currentEditNodeId;
  const lineId = document.getElementById('editLineId').value.trim();
  const voltageText = document.getElementById('editVoltage').value.trim();
  const voltage = Number(voltageText);
  const status = document.getElementById('editStatus').value;
  const repairTimeText = document.getElementById('repairTimeMinutes').value.trim();
  const error = document.getElementById('editError');
  const applyButton = document.getElementById('applyEditButton');
  if (!voltageText || !Number.isFinite(voltage) || voltage <= 0 || voltage > 1000) {
    error.textContent = 'Voltage must be a positive number no greater than 1000 volts.';
    document.getElementById('editVoltage').focus();
    return;
  }
  if (!nodeId
      && !(currentNetworkState?.lines || []).some((line) => line.id === lineId)) {
    error.textContent = 'Select a valid incident line.';
    document.getElementById('editLineId').focus();
    return;
  }
  if (!nodeId && !['WORKING', 'FAILED', 'STANDBY'].includes(status)) {
    error.textContent = 'Select a valid network status.';
    document.getElementById('editStatus').focus();
    return;
  }
  let repairTimeMinutes;
  if (!nodeId && status === 'FAILED') {
    if (!/^[0-9]+$/.test(repairTimeText)
        || !Number.isSafeInteger(Number(repairTimeText))
        || Number(repairTimeText) < 1
        || Number(repairTimeText) > 2147483647) {
      error.textContent = 'Repair time must be a positive whole number of minutes.';
      document.getElementById('repairTimeMinutes').focus();
      return;
    }
    repairTimeMinutes = Number(repairTimeText);
  }
  error.textContent = '';
  manualEditPending = true;
  manualEditController = new AbortController();
  applyButton.disabled = true;
  applyButton.textContent = 'Applying…';
  const requestPath = nodeId ? '/node-voltage' : '/action';
  const requestBody = nodeId
    ? { nodeId, newVoltage: voltage }
    : {
      lineId,
      newVoltage: voltage,
      newStatus: status,
      ...(status === 'FAILED' ? { repairTimeMinutes } : {})
    };
  let detectedFaultCount = null;
  const confirmLineResponse = (result) => {
    const line = result?.line;
    if (result?.status !== 'ok' || line?.id !== lineId
        || !Number.isFinite(line.observedVoltage)
        || Math.abs(line.observedVoltage - voltage) > 0.000001) {
      throw createRequestError(
        'The backend response did not confirm the selected line and voltage.', true);
    }
    if (status === 'FAILED'
        && (line.status !== 'FAILED' || line.repairTimeMinutes !== repairTimeMinutes)) {
      throw createRequestError(
        'The backend response did not confirm the FAILED status and repair time.', true);
    }
    if (status === 'STANDBY' && line.status !== 'STANDBY') {
      throw createRequestError('The backend did not confirm STANDBY status.', true);
    }
    if (status === 'WORKING' && !['WORKING', 'FAILED'].includes(line.status)) {
      throw createRequestError('The backend did not confirm the line status.', true);
    }
    return line;
  };

  const refreshAndCheckLine = async () => {
    await refreshLiveData();
    if (nodeId) {
      const node = (currentNetworkState?.nodes || []).find((item) => item.id === nodeId);
      if (node && Math.abs(Number(node.observedVoltage) - voltage) <= 0.000001) {
        return { nodeId: node.id, observedVoltage: Number(node.observedVoltage) };
      }
      return null;
    }
    const line = (currentNetworkState?.lines || []).find((item) => item.id === lineId);
    if (!line || Math.abs(Number(line.observedVoltage) - voltage) > 0.000001) {
      return null;
    }
    if (status === 'FAILED'
        && (line.status !== 'FAILED'
          || Number(line.repairTimeMinutes) !== repairTimeMinutes)) {
      return null;
    }
    if (status === 'STANDBY' && line.status !== 'STANDBY') {
      return null;
    }
    if (status === 'WORKING' && !['WORKING', 'FAILED'].includes(line.status)) {
      return null;
    }
    return line;
  };

  try {
    const result = await requestJson(requestPath, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(requestBody),
      signal: manualEditController.signal
    });
    let confirmedLine = null;
    if (nodeId) {
      if (result?.status !== 'ok' || result.node?.id !== nodeId
          || !Number.isFinite(result.node.observedVoltage)
          || Math.abs(result.node.observedVoltage - voltage) > 0.000001) {
        throw createRequestError('The backend did not confirm the node voltage update.', true);
      }
      detectedFaultCount = Number.isSafeInteger(result.detectedFaultCount)
        ? result.detectedFaultCount : null;
    } else {
      confirmedLine = confirmLineResponse(result);
    }
    closeEditModal();
    try {
      await refreshLiveData();
      const appliedDescription = confirmedLine
        ? `${confirmedLine.id} is ${confirmedLine.status} at ${confirmedLine.observedVoltage} V.`
        : detectedFaultCount > 0
          ? `${nodeId} voltage update was confirmed; ${detectedFaultCount} fault(s) detected and recorded.`
          : detectedFaultCount === 0
            ? `${nodeId} voltage update was confirmed; no new faults detected.`
            : `${nodeId} voltage update was confirmed.`;
      updateStatus(`Update confirmed by the backend; ${appliedDescription} Network, outage and restoration data refreshed.`);
    } catch (refreshError) {
      const appliedDescription = confirmedLine
        ? `${confirmedLine.id} was updated.` : `${nodeId} was updated.`;
      error.textContent = `The backend confirmed the update, but live data refresh failed: ${refreshError.message}`;
      updateStatus(`${appliedDescription} Live data refresh failed: ${refreshError.message}`);
    }
  } catch (requestError) {
    if (requestError.reconcileRequired) {
      try {
        const reconciledLine = await refreshAndCheckLine();
        if (reconciledLine) {
          closeEditModal();
          if (nodeId) {
            updateStatus(
              `Node voltage update confirmed from backend state; ${nodeId} is at ${reconciledLine.observedVoltage} V. Network, outage and restoration data refreshed.`);
          } else {
            updateStatus(
              `Update confirmed from backend state; ${reconciledLine.id} is ${reconciledLine.status} at ${reconciledLine.observedVoltage} V. Network, outage and restoration data refreshed.`);
          }
          return;
        }
      } catch (refreshError) {
        requestError.message += ` Live data refresh failed: ${refreshError.message}`;
      }
    }
    error.textContent = requestError.message;
    updateStatus(`Manual update failed: ${requestError.message}`);
  } finally {
    manualEditPending = false;
    manualEditController = null;
    applyButton.disabled = false;
    applyButton.textContent = 'Apply Changes';
  }
}

function initializeCanvasEditing() {
  const canvas = document.getElementById('networkCanvas');
  const modal = document.getElementById('editModal');
  if (!canvas || !modal) {
    return;
  }
  const sidebar = document.querySelector('[data-sidebar-toggle]');
  sidebar?.addEventListener('click', (event) => {
    const button = event.currentTarget;
    const shell = document.querySelector('.app-shell');
    const collapsed = shell.classList.toggle('sidebar-collapsed');
    button.setAttribute('aria-expanded', String(!collapsed));
    button.setAttribute('aria-label', collapsed ? 'Expand navigation' : 'Collapse navigation');
    button.textContent = collapsed ? '›' : '‹';
  });
  canvas.addEventListener('click', (event) => {
    if (suppressGraphClick) {
      suppressGraphClick = false;
      return;
    }
    const selection = findCanvasSelection(canvas, event);
    if (selection) {
      if (document.body.classList.contains('dashboard-page')) {
        selectDashboardNetworkItem(selection);
      } else {
        openEditModal(selection);
      }
    }
  });
  canvas.addEventListener('pointerdown', (event) => {
    if (event.button !== 0) {
      return;
    }
    graphPointer = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      offsetX: graphTransform.x,
      offsetY: graphTransform.y,
      moved: false
    };
    canvas.setPointerCapture(event.pointerId);
  });
  canvas.addEventListener('pointermove', (event) => {
    if (!graphPointer || graphPointer.pointerId !== event.pointerId) {
      return;
    }
    const deltaX = event.clientX - graphPointer.startX;
    const deltaY = event.clientY - graphPointer.startY;
    if (Math.hypot(deltaX, deltaY) > 3) {
      graphPointer.moved = true;
      canvas.classList.add('is-panning');
    }
    if (!graphPointer.moved) {
      return;
    }
    const bounds = canvas.getBoundingClientRect();
    graphTransform.x = graphPointer.offsetX
      + deltaX * (canvas.width / bounds.width);
    graphTransform.y = graphPointer.offsetY
      + deltaY * (canvas.height / bounds.height);
    if (currentNetworkState) {
      drawNetwork(canvas, currentNetworkState);
    }
  });
  const finishPan = (event) => {
    if (!graphPointer || graphPointer.pointerId !== event.pointerId) {
      return;
    }
    suppressGraphClick = graphPointer.moved;
    graphPointer = null;
    canvas.classList.remove('is-panning');
    window.setTimeout(() => {
      suppressGraphClick = false;
    }, 0);
  };
  canvas.addEventListener('pointerup', finishPan);
  canvas.addEventListener('pointercancel', finishPan);
  canvas.addEventListener('wheel', (event) => {
    event.preventDefault();
    const point = canvasPoint(canvas, event);
    zoomNetworkCanvas(
      canvas,
      graphTransform.scale * (event.deltaY < 0 ? 1.1 : 0.9),
      point);
  }, { passive: false });
  document.querySelectorAll('[data-network-zoom]').forEach((button) => {
    button.addEventListener('click', () => {
      if (button.dataset.networkZoom === 'reset') {
        graphTransform = { scale: 1, x: 0, y: 0 };
        if (currentNetworkState) {
          drawNetwork(canvas, currentNetworkState);
        }
      } else {
        zoomNetworkCanvas(
          canvas,
          graphTransform.scale * (button.dataset.networkZoom === 'in' ? 1.2 : 0.84));
      }
    });
  });
  document.querySelector('[data-selection-edit]')?.addEventListener('click', () => {
    if (!currentGraphSelection || !currentNetworkState) {
      return;
    }
    const selection = currentGraphSelection.nodeId
      ? {
        nodeId: currentGraphSelection.nodeId,
        lines: (currentNetworkState.lines || []).filter((line) =>
          line.from === currentGraphSelection.nodeId
          || line.to === currentGraphSelection.nodeId)
      }
      : {
        lines: (currentNetworkState.lines || []).filter((line) =>
          line.id === currentGraphSelection.lineId)
      };
    if (selection.lines.length) {
      openEditModal(selection);
    }
  });
  document.getElementById('editLineId').addEventListener('change', (event) => {
    const updated = setEditLine(event.target.value);
    document.getElementById('editError').textContent = updated
      ? '' : 'Select a valid incident line.';
  });
  document.getElementById('editStatus').addEventListener('change', updateRepairTimeField);
  document.getElementById('editForm').addEventListener('submit', applyManualEdit);
  modal.querySelectorAll('[data-modal-cancel]').forEach((button) => {
    button.addEventListener('click', () => {
      manualEditController?.abort();
      closeEditModal();
    });
  });
  document.addEventListener('keydown', (event) => {
    if (event.key === 'Escape' && !modal.hidden) {
      manualEditController?.abort();
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
    appendCell(row, Number.isFinite(line.observedVoltage)
      ? `${line.observedVoltage} V` : null);
    appendCell(row, Number.isFinite(line.observedCurrent)
      ? `${line.observedCurrent} A` : null);
    appendCell(row, formatAffectedNodes(line.affectedConsumers));
    appendCell(row, formatAffectedNodes(line.criticalFacilities));
    appendCell(row, line.assignedTeam);
    appendCell(row, `${line.repairTimeMinutes ?? 0} min`);
    appendCell(row, `${line.travelTimeMinutes ?? 0} min`);
    appendCell(row, `${line.estimatedRestorationTimeMinutes ?? 0} min`);
    const hasFaultReport = !['NO_FAULT', 'FAILED_UNREPORTED'].includes(line.repairStatus);
    appendCell(row, hasFaultReport
      ? (line.affectedConsumersRestored ? 'YES' : 'NO') : null);
    appendCell(row, hasFaultReport
      ? (line.networkRestored ? 'YES' : 'NO') : null);
    body.appendChild(row);
  }
}

function renderOutages(payload) {
  const body = document.querySelector('#outagesTable tbody');
  if (!body) {
    return;
  }
  body.replaceChildren();
  const outages = payload.outages || [];
  if (outages.length === 0) {
    renderEmptyTableRow(body, 10, 'No active faults in the Java restoration queue.');
    renderRestorationReports(payload.restorationReports || []);
    return;
  }
  for (const outage of outages) {
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
  const teams = payload.teams || [];
  if (teams.length === 0) {
    renderEmptyTableRow(body, 8, 'No repair teams are reported by the backend.');
    return;
  }
  for (const team of teams) {
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
  const events = payload.history || [];
  const dateFilter = document.getElementById('historyDateFilter')?.value || '';
  const filteredEvents = dateFilter
    ? events.filter((event) => event.timestamp?.slice(0, 10) === dateFilter)
    : events;
  const faultCount = filteredEvents.filter((event) =>
    event.eventType === 'FAULT_DETECTED').length;
  const summary = document.getElementById('historySummary');
  if (summary) {
    summary.textContent = dateFilter
      ? `${faultCount} fault${faultCount === 1 ? '' : 's'} detected on ${dateFilter}`
      : `${faultCount} fault${faultCount === 1 ? '' : 's'} detected across all dates`;
  }
  const updated = document.getElementById('historyLastUpdated');
  if (updated) {
    updated.textContent = `Updated ${new Date().toLocaleTimeString()}`;
  }
  if (filteredEvents.length === 0) {
    renderEmptyTableRow(
      body,
      6,
      dateFilter ? `No history events recorded on ${dateFilter}.`
        : 'No outage history is recorded.');
    return;
  }
  for (const event of filteredEvents) {
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
        button.disabled = false;
        updateStatus(`Backup link ${action} failed: ${error.message}`);
      });
  });
  return button;
}

function refreshLiveData() {
  const dashboardPage = document.body.classList.contains('dashboard-page');
  const requests = [];
  const addRequest = (name, load) => requests.push({ name, load });

  if (dashboardPage || document.querySelector('#networkCanvas, #networkLinesTable')) {
    addRequest('network', fetchNetworkState);
  }
  if (dashboardPage || document.querySelector('#outagesTable, #restorationTable')) {
    addRequest('outages', fetchOutageQueue);
  }
  if (dashboardPage || document.querySelector('#teamsTable')) {
    addRequest('repair teams', fetchRepairTeams);
  }
  if (dashboardPage || document.querySelector('#historyTable')) {
    addRequest('history', fetchHistory);
  }
  if (dashboardPage || document.querySelector('#backupApprovalsTable')) {
    addRequest('backup approvals', fetchBackupApprovals);
  }

  if (requests.length === 0) {
    addRequest('network', fetchNetworkState);
    addRequest('outages', fetchOutageQueue);
    addRequest('repair teams', fetchRepairTeams);
    addRequest('history', fetchHistory);
    addRequest('backup approvals', fetchBackupApprovals);
  }

  return Promise.allSettled(requests.map((request) => request.load())).then((results) => {
    const failures = results.flatMap((result, index) => result.status === 'rejected'
      ? [`${requests[index].name}: ${result.reason.message}`]
      : []);
    if (failures.length > 0) {
      throw createRequestError(`Live data partially unavailable — ${failures.join('; ')}`, true);
    }
    return results.map((result) => result.value);
  });
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
    return refreshLiveData().then(() => result);
  });
}

function autoDetect() {
  const eventTypes = ['Storm', 'Peak Load Overload', 'Random Aging Fault'];
  const eventType = eventTypes[Math.floor(Math.random() * eventTypes.length)];
  console.log('Auto Detect selected event:', eventType);
  return postAction('/actions/simulate-fault', { eventType });
}

function restoreFaults() {
  return fetchOutageQueue().then((payload) => {
    if (payload.outages.length === 0) {
      return { noPendingFaults: true };
    }
    return postAction('/actions/auto-restore');
  });
}

function runFrontendAction(action) {
  const operations = {
    autoDetect,
    restore: restoreFaults,
    reset: () => postAction('/actions/reset')
  };
  const operation = operations[action];
  if (!operation) {
    return;
  }
  updateStatus('Working…');
  operation()
    .then((result) => updateStatus(result?.noPendingFaults
      ? 'No pending faults to restore.'
      : 'Live data refreshed.'))
    .catch((error) => {
      if (action === 'restore'
          && error.message.includes('There are no pending faults to restore.')) {
        refreshLiveData()
          .then(() => updateStatus('No pending faults to restore.'))
          .catch((refreshError) => {
            console.error('Unable to refresh after restore check:', refreshError);
            updateStatus(
              `No pending faults to restore. Live data refresh failed: ${refreshError.message}`);
          });
        return;
      }
      if (action === 'restore' && error.reconcileRequired) {
        refreshLiveData()
          .then(() => updateStatus(
            `Restore response failed; live state refreshed. ${error.message}`))
          .catch((refreshError) => {
            console.error('Unable to reconcile restore state:', refreshError);
            updateStatus(
              'Restore response failed and live state could not be refreshed: '
              + refreshError.message);
          });
        return;
      }
      console.error('Grid operation failed:', error);
      updateStatus(`Operation failed: ${error.message}`);
    });
}

function initializeLivePages() {
  initializeCanvasEditing();
  document.querySelector('[data-refresh-history]')?.addEventListener('click', (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    button.textContent = 'Refreshing…';
    updateStatus('Refreshing history…');
    fetchHistory()
      .then(() => updateStatus('Connected to live Java API.'))
      .catch((error) => {
        console.error('Unable to refresh outage history:', error);
        updateStatus(`Unable to refresh history: ${error.message}`);
      })
      .finally(() => {
        button.disabled = false;
        button.textContent = 'Refresh';
      });
  });
  document.getElementById('historyDateFilter')?.addEventListener('change', () => {
    if (dashboardData.history) {
      renderHistory(dashboardData.history);
    }
  });
  document.getElementById('clearHistoryDateFilter')?.addEventListener('click', () => {
    const dateFilter = document.getElementById('historyDateFilter');
    if (dateFilter) {
      dateFilter.value = '';
    }
    if (dashboardData.history) {
      renderHistory(dashboardData.history);
    }
  });
  document.querySelector('[data-refresh-dashboard]')?.addEventListener('click', (event) => {
    const button = event.currentTarget;
    button.disabled = true;
    button.textContent = 'Refreshing…';
    updateStatus('Refreshing live data…');
    refreshLiveData()
      .then(() => updateStatus('Connected to live Java API.'))
      .catch((error) => {
        console.error('Unable to refresh live grid data:', error);
        updateStatus(`Unable to refresh live data: ${error.message}`);
      })
      .finally(() => {
        button.disabled = false;
        button.innerHTML = '<span aria-hidden="true">↻</span> Refresh';
      });
  });
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
  window.setInterval(() => {
    if (document.hidden || automaticRefreshInProgress) {
      return;
    }
    automaticRefreshInProgress = true;
    refreshLiveData()
      .then(() => updateStatus('Connected to live Java API.'))
      .catch((error) => {
        console.error('Unable to refresh live grid data:', error);
        updateStatus(`Unable to refresh live data: ${error.message}`);
      })
      .finally(() => {
        automaticRefreshInProgress = false;
      });
  }, LIVE_REFRESH_INTERVAL_MS);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', initializeLivePages);
} else {
  initializeLivePages();
}

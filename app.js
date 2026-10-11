(function () {
  const API_BASE = location.protocol === 'file:' ? 'http://localhost:8080' : '';

  function fetchJson(url) {
    return fetch(url, {
      headers: {
        Accept: 'application/json'
      }
    }).then(async (response) => {
      const text = await response.text();
      if (!response.ok) {
        throw new Error(text || 'Request failed');
      }
      if (!text) {
        return {};
      }
      try {
        return JSON.parse(text);
      } catch (error) {
        throw new Error('Failed to parse JSON from ' + url + ': ' + error.message);
      }
    });
  }

  function renderNetwork(payload) {
    const canvas = document.getElementById('networkCanvas');
    if (!canvas) {
      return;
    }

    const ctx = canvas.getContext('2d');
    ctx.clearRect(0, 0, canvas.width, canvas.height);

    const nodeMap = new Map();
    for (const node of payload.nodes || []) {
      nodeMap.set(node.id, node);
    }

    const points = new Map();
    for (const node of payload.nodes || []) {
      const x = Number(node.x ?? 60 + (Math.random() * 300));
      const y = Number(node.y ?? 60 + (Math.random() * 300));
      points.set(node.id, { x, y });
    }

    if (payload.lines && payload.lines.length > 0) {
      for (const line of payload.lines) {
        const from = points.get(line.from);
        const to = points.get(line.to);
        if (!from || !to) {
          continue;
        }

        ctx.beginPath();
        ctx.moveTo(from.x, from.y);
        ctx.lineTo(to.x, to.y);
        ctx.lineWidth = line.backup ? 2 : 4;
        ctx.strokeStyle = line.status === 'FAILED' ? '#dc3d4b' : (line.backup ? '#4c7ae7' : '#5cb081');
        ctx.setLineDash(line.backup ? [8, 8] : []);
        ctx.stroke();
        ctx.setLineDash([]);
      }
    }

    for (const node of payload.nodes || []) {
      const point = points.get(node.id);
      if (!point) {
        continue;
      }

      const radius = node.nodeType === 'Substation' ? 22 : node.nodeType === 'Feeder' ? 18 : 16;
      ctx.beginPath();
      ctx.arc(point.x, point.y, radius, 0, Math.PI * 2);
      ctx.fillStyle = node.nodeType === 'Substation'
        ? '#dfeeff'
        : node.nodeType === 'Feeder'
          ? '#eafaf3'
          : node.nodeType === 'Hospital' || node.nodeType === 'EmergencyCentre'
            ? '#ffe7eb'
            : '#fff0d9';
      ctx.fill();
      ctx.lineWidth = 2;
      ctx.strokeStyle = node.powered ? '#2364d2' : '#dc3d4b';
      ctx.stroke();

      ctx.fillStyle = '#1a2433';
      ctx.font = '12px sans-serif';
      ctx.textAlign = 'center';
      ctx.fillText(node.id, point.x, point.y + 4);
    }
  }

  function renderHistory(payload) {
    const tableBody = document.querySelector('#historyTable tbody');
    if (!tableBody) {
      return;
    }

    tableBody.innerHTML = '';
    for (const row of payload.history || []) {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${row.timestamp || ''}</td>
        <td>${row.eventType || ''}</td>
        <td>${row.lineId || ''}</td>
        <td>${row.affectedZones || ''}</td>
        <td>${row.assignedTeam || ''}</td>
        <td>${row.status || ''}</td>
      `;
      tableBody.appendChild(tr);
    }
  }

  function renderTeams(payload) {
    const tableBody = document.querySelector('#teamsTable tbody');
    if (!tableBody) {
      return;
    }

    tableBody.innerHTML = '';
    for (const team of payload.teams || []) {
      const tr = document.createElement('tr');
      tr.innerHTML = `
        <td>${team.id || ''}</td>
        <td>${team.name || ''}</td>
        <td>${team.depot || ''}</td>
        <td>${team.status || ''}</td>
      `;
      tableBody.appendChild(tr);
    }
  }

  async function initDashboard() {
    const urlConfig = {
      network: `${API_BASE}/api/network`,
      history: `${API_BASE}/api/history`,
      teams: `${API_BASE}/api/teams`
    };

    try {
      const [network, history, teams] = await Promise.all([
        fetchJson(urlConfig.network),
        fetchJson(urlConfig.history),
        fetchJson(urlConfig.teams)
      ]);

      renderNetwork(network);
      renderHistory(history);
      renderTeams(teams);
    } catch (error) {
      console.error('Fetch failed', error);
      const status = document.getElementById('statusText');
      if (status) {
        status.textContent = 'Unable to load live grid data from backend.';
      }
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initDashboard);
  } else {
    initDashboard();
  }
})();

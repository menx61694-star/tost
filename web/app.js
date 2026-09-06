const $ = id => document.getElementById(id);

let authToken = "";
let dashboardSession = "";
let socket = null;
let devices = [];
const maps = new Map();
const liveStates = new Map();
let liveReconnectTimer = null;
let liveReconnectAttempt = 0;
let manualDisconnect = false;

$("connect").onclick = async () => {
  const token = $("token").value.trim();
  if (!token) return;
  manualDisconnect = false;
  authToken = token;
  liveReconnectAttempt = 0;
  if (liveReconnectTimer) clearTimeout(liveReconnectTimer);

  try {
    await refreshDashboardSession();
    $("server").textContent = "Connected";
    render();
    connectLive();
  } catch (e) {
    $("server").textContent = e.unauthorized ? "Unauthorized" : "Connection failed";
    $("devices").textContent = e.message;
  }
};

async function refreshDashboardSession() {
  const r = await fetch("/api/devices", { headers: { Authorization: `Bearer ${authToken}` } });
  if (!r.ok) {
    const error = new Error(r.status === 401 ? "Unauthorized" : `Server error (${r.status})`);
    error.unauthorized = r.status === 401;
    error.status = r.status;
    throw error;
  }
  devices = await r.json();
  const sessionResponse = await fetch("/api/dashboard-session", {
    method: "POST", headers: { Authorization: `Bearer ${authToken}` }
  });
  if (!sessionResponse.ok) {
    const error = new Error(sessionResponse.status === 401 ? "Unauthorized" : `Could not create live dashboard session (${sessionResponse.status})`);
    error.unauthorized = sessionResponse.status === 401;
    error.status = sessionResponse.status;
    throw error;
  }
  dashboardSession = (await sessionResponse.json()).token;
}

function connectLive() {
  if (manualDisconnect || !authToken || !dashboardSession) return;
  if (socket) {
    socket.onclose = null;
    socket.close();
  }
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  const currentSocket = new WebSocket(`${protocol}//${location.host}/ws?session=${encodeURIComponent(dashboardSession)}`);
  socket = currentSocket;
  currentSocket.onopen = () => {
    if (socket !== currentSocket) return;
    liveReconnectAttempt = 0;
    if (liveReconnectTimer) clearTimeout(liveReconnectTimer);
    liveReconnectTimer = null;
    currentSocket.send(JSON.stringify({ type: "dashboard_hello" }));
    $("server").textContent = "Live";
  };
  currentSocket.onmessage = event => {
    if (socket !== currentSocket) return;
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message.type === "devices" && Array.isArray(message.devices)) {
      devices = message.devices;
      render();
    } else if (message.type === "command_result") {
      const result = message.result || {};
      if (result.ok && result.locationSessionActive !== undefined) {
        liveStates.set(message.deviceId, result);
        renderLiveStatus(message.deviceId, result);
      }
      if (result.ok && result.latitude !== undefined && result.longitude !== undefined) {
        if (result.timestamp === undefined) result.timestamp = result.locationTimestamp;
        updateMap(message.deviceId, result);
      }
      if (result.ok && Array.isArray(result.workouts)) renderHistory(message.deviceId, result.workouts);
      if (result.ok && result.workout) showWorkout(message.deviceId, result.workout);
      if (result.ok && result.silent) return;
      showMessage(`Command result: ${result.ok ? formatResult(result) : (result.error || "Command failed")}`);
    }
  };
  currentSocket.onclose = () => {
    if (socket !== currentSocket) return;
    socket = null;
    if (manualDisconnect || !authToken) return;
    $("server").textContent = "Live disconnected; reconnecting…";
    scheduleLiveReconnect();
  };
  currentSocket.onerror = () => {
    if (socket === currentSocket) $("server").textContent = "Live connection error";
  };
}

function scheduleLiveReconnect() {
  if (manualDisconnect || !authToken || liveReconnectTimer) return;
  const delayMs = Math.min(60_000, 2_000 * (2 ** Math.min(liveReconnectAttempt, 5)));
  liveReconnectAttempt++;
  liveReconnectTimer = setTimeout(async () => {
    liveReconnectTimer = null;
    if (manualDisconnect || !authToken) return;
    try {
      await refreshDashboardSession();
      render();
      connectLive();
    } catch (e) {
      if (e.unauthorized) {
        dashboardSession = "";
        $("server").textContent = "Unauthorized";
        return;
      }
      $("server").textContent = "Server unavailable; retrying…";
      scheduleLiveReconnect();
    }
  }, delayMs);
}

function render() {
  const root = $("devices");
  root.innerHTML = "";
  for (const entry of maps.values()) entry.map.remove();
  maps.clear();
  if (!devices.length) { root.textContent = "No devices connected."; return; }

  for (const d of devices) {
    const card = document.createElement("article");
    card.className = "device";
    const details = document.createElement("div");
    const name = document.createElement("strong");
    name.textContent = d.info?.model ? `${d.info.manufacturer || ""} ${d.info.model}`.trim() : d.deviceId;
    const state = document.createElement("small");
    state.textContent = `${d.status} · last seen ${new Date(d.lastSeen).toLocaleTimeString()}`;
    details.append(name, state);

    const liveStatus = document.createElement("div");
    liveStatus.className = "live-status";
    liveStatus.id = `live-status-${d.deviceId}`;

    const actions = document.createElement("div");
    for (const [label, commandName] of [
      ["Status", "get_status"], ["Device info", "get_device_info"], ["Battery", "get_battery"],
      ["Network", "get_network"], ["Permissions", "get_permissions"], ["Contacts count", "get_contacts_count"],
      ["Calendar count", "get_calendar_count"], ["Location", "get_location"], ["Workout history", "get_workout_history"]
    ]) {
      const button = document.createElement("button");
      button.textContent = label;
      button.disabled = d.status !== "online";
      button.onclick = () => command(d.deviceId, commandName);
      actions.appendChild(button);
    }

    const mapHost = document.createElement("div");
    mapHost.className = "map-host";
    const historyHost = document.createElement("div");
    historyHost.className = "history-host";
    historyHost.id = `history-${d.deviceId}`;
    card.append(details, liveStatus, actions, mapHost, historyHost);
    root.appendChild(card);

    if (typeof L !== "undefined") {
      const map = L.map(mapHost, { zoomControl: true }).setView([20, 0], 2);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19, attribution: "&copy; OpenStreetMap contributors"
      }).addTo(map);
      maps.set(d.deviceId, { map, marker: null, routeLine: null });
    }

    const previousState = liveStates.get(d.deviceId);
    if (previousState) renderLiveStatus(d.deviceId, previousState);
  }
}

function renderLiveStatus(deviceId, result) {
  const host = $(`live-status-${deviceId}`);
  if (!host) return;
  const metrics = result.metrics || {};
  const active = result.locationSessionActive === true;
  const paused = result.locationSessionPaused === true;
  const stepsAvailable = result.stepsAvailable !== false && metrics.stepsAvailable !== false;
  const stateText = !active ? "Location session: stopped" : paused ? "Location session: paused" : "Location session: running";
  const items = [
    ["State", stateText],
    ["Steps", stepsAvailable ? formatSteps(result.steps ?? metrics.steps) : "Unavailable"],
    ["Distance", formatDistance(metrics.distanceMeters)],
    ["Duration", formatDuration(metrics.durationSeconds)],
    ["Avg speed", formatSpeed(metrics.averageSpeedMps)],
    ["Pace", formatPace(metrics.paceSecondsPerKm)]
  ];
  host.innerHTML = "";
  for (const [label, value] of items) {
    const item = document.createElement("div");
    item.className = "live-stat";
    const valueNode = document.createElement("strong");
    valueNode.textContent = value;
    const labelNode = document.createElement("small");
    labelNode.textContent = label;
    item.append(valueNode, labelNode);
    host.appendChild(item);
  }
  if (result.latitude !== undefined && result.longitude !== undefined) {
    const location = document.createElement("small");
    location.className = "live-location";
    const age = result.locationTimestamp ? Math.max(0, Math.round((Date.now() - result.locationTimestamp) / 1000)) : null;
    location.textContent = `GPS ${Number(result.latitude).toFixed(6)}, ${Number(result.longitude).toFixed(6)} · accuracy ${formatAccuracy(result.accuracyMeters)}${age === null ? "" : ` · ${age}s old`}`;
    host.appendChild(location);
  }
}

async function command(deviceId, commandName, extra = {}, options = {}) {
  try {
    const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${authToken}` },
      body: JSON.stringify({ command: commandName, ...extra })
    });
    const data = await r.json();
    if (!r.ok || !data.ok) throw new Error(data.error || "Command failed");
    if (!options.silent) showMessage(`Command sent: ${data.id}`);
  } catch (e) { if (!options.silent) showMessage(e.message); }
}

function updateMap(deviceId, result) {
  const entry = maps.get(deviceId);
  if (!entry) return;
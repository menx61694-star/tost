const $ = id => document.getElementById(id);

let authToken = "";
let dashboardSession = "";
let socket = null;
let devices = [];
const maps = new Map();
const liveStates = new Map();

$("connect").onclick = async () => {
  const token = $("token").value.trim();
  if (!token) return;
  authToken = token;

  try {
    const r = await fetch("/api/devices", { headers: { Authorization: `Bearer ${authToken}` } });
    if (!r.ok) throw new Error("Unauthorized");
    devices = await r.json();
    const sessionResponse = await fetch("/api/dashboard-session", {
      method: "POST", headers: { Authorization: `Bearer ${authToken}` }
    });
    if (!sessionResponse.ok) throw new Error("Could not create live dashboard session");
    dashboardSession = (await sessionResponse.json()).token;
    $("server").textContent = "Connected";
    render();
    connectLive();
  } catch (e) {
    $("server").textContent = "Connection failed";
    $("devices").textContent = e.message;
  }
};

function connectLive() {
  if (socket) socket.close();
  const protocol = location.protocol === "https:" ? "wss:" : "ws:";
  socket = new WebSocket(`${protocol}//${location.host}/ws?session=${encodeURIComponent(dashboardSession)}`);
  socket.onopen = () => {
    socket.send(JSON.stringify({ type: "dashboard_hello" }));
    $("server").textContent = "Live";
  };
  socket.onmessage = event => {
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
      if (result.ok && message.id && result.commandId === message.id) {
        // Reserved for future per-command UI handling.
      }
      if (result.ok && result.silent) return;
      showMessage(`Command result: ${result.ok ? formatResult(result) : (result.error || "Command failed")}`);
    }
  };
  socket.onclose = () => { if (authToken) $("server").textContent = "Live disconnected"; };
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
  const latitude = Number(result.latitude), longitude = Number(result.longitude);
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude) || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) return;

  const route = Array.isArray(result.route) ? result.route.map(point => [Number(point.latitude), Number(point.longitude)])
    .filter(([lat, lon]) => Number.isFinite(lat) && Number.isFinite(lon) && Math.abs(lat) <= 90 && Math.abs(lon) <= 180) : [];
  const linePoints = route.length >= 2 ? route : [[latitude, longitude]];
  if (entry.routeLine) entry.routeLine.setLatLngs(linePoints);
  else if (route.length >= 2) entry.routeLine = L.polyline(route, { weight: 4 }).addTo(entry.map);

  const metrics = result.metrics || {};
  const stepsText = metrics.stepsAvailable === false ? "Steps: unavailable" : `Steps: ${formatSteps(metrics.steps)}`;
  const timestamp = Number(result.timestamp);
  const updatedText = Number.isFinite(timestamp) && timestamp > 0 ? new Date(timestamp).toLocaleString() : "unknown";
  const popup = `Accuracy: ${formatAccuracy(result.accuracyMeters)}<br>Updated: ${updatedText}<br>Distance: ${formatDistance(metrics.distanceMeters)} · ${formatDuration(metrics.durationSeconds)}<br>Avg speed: ${formatSpeed(metrics.averageSpeedMps)} · Pace: ${formatPace(metrics.paceSecondsPerKm)}<br>${stepsText}<br>Route points: ${route.length}`;
  if (!entry.marker) entry.marker = L.marker([latitude, longitude]).addTo(entry.map);
  else entry.marker.setLatLng([latitude, longitude]);
  entry.marker.bindPopup(popup);

  if (route.length >= 2) entry.map.fitBounds(entry.routeLine.getBounds(), { padding: [24, 24], maxZoom: 17 });
  else entry.map.setView([latitude, longitude], 16);
  setTimeout(() => entry.map.invalidateSize(), 0);
}

function renderHistory(deviceId, workouts) {
  const host = $(`history-${deviceId}`);
  if (!host) return;
  host.innerHTML = "";
  const title = document.createElement("h3");
  title.textContent = `Workout history (${workouts.length})`;
  host.appendChild(title);

  if (!workouts.length) {
    const empty = document.createElement("small");
    empty.textContent = "No completed workouts yet.";
    host.appendChild(empty);
    return;
  }

  const totalDistance = workouts.reduce((sum, w) => sum + Math.max(0, Number(w.distanceMeters) || 0), 0);
  const totalDuration = workouts.reduce((sum, w) => sum + Math.max(0, Number(w.durationSeconds) || 0), 0);
  const totalSteps = workouts.reduce((sum, w) => sum + Math.max(0, Number(w.steps) || 0), 0);
  const stats = document.createElement("div");
  stats.className = "history-stats";
  for (const [label, value] of [
    ["Workouts", workouts.length.toLocaleString()],
    ["Distance", formatDistance(totalDistance)],
    ["Time", formatDuration(totalDuration)],
    ["Steps", workouts.some(w => w.stepsAvailable !== false) ? formatSteps(totalSteps) : "—"]
  ]) {
    const stat = document.createElement("div");
    stat.className = "history-stat";
    const valueNode = document.createElement("strong");
    valueNode.textContent = value;
    const labelNode = document.createElement("small");
    labelNode.textContent = label;
    stat.append(valueNode, labelNode);
    stats.appendChild(stat);
  }
  host.appendChild(stats);

  for (const workout of workouts) {
    const item = document.createElement("div");
    item.className = "workout";
    const info = document.createElement("div");
    const date = new Date(Number(workout.startTime));
    const heading = document.createElement("strong");
    heading.textContent = Number.isFinite(date.getTime()) ? date.toLocaleString() : "Workout";
    const stats = document.createElement("small");
    const steps = workout.stepsAvailable === false ? "steps unavailable" : `${formatSteps(workout.steps)} steps`;
    stats.textContent = `${formatDistance(workout.distanceMeters)} · ${formatDuration(workout.durationSeconds)} · ${formatSpeed(workout.averageSpeedMps)} · ${steps}`;
    info.append(heading, stats);
    const view = document.createElement("button");
    view.textContent = "View route";
    view.onclick = () => command(deviceId, "get_workout", { workoutId: workout.id });
    item.append(info, view);
    host.appendChild(item);
  }
}

function showWorkout(deviceId, workout) {
  const route = Array.isArray(workout.route) ? workout.route : [];
  if (route.length >= 1) {
    const first = route[0];
    const result = {
      latitude: first.latitude,
      longitude: first.longitude,
      accuracyMeters: first.accuracyMeters,
      timestamp: first.timestamp,
      route,
      metrics: {
        distanceMeters: workout.distanceMeters,
        durationSeconds: workout.durationSeconds,
        averageSpeedMps: workout.averageSpeedMps,
        paceSecondsPerKm: workout.paceSecondsPerKm,
        steps: workout.steps,
        stepsAvailable: workout.stepsAvailable
      }
    };
    updateMap(deviceId, result);
  }
  const host = $(`history-${deviceId}`);
  if (host) {
    const detail = document.createElement("div");
    detail.className = "workout-detail-card";
    const title = document.createElement("h4");
    title.textContent = "Workout details";
    const grid = document.createElement("div");
    grid.className = "workout-stat-grid";
    const stats = [
      ["Distance", formatDistance(workout.distanceMeters)],
      ["Duration", formatDuration(workout.durationSeconds)],
      ["Avg speed", formatSpeed(workout.averageSpeedMps)],
      ["Pace", formatPace(workout.paceSecondsPerKm)],
      ["Steps", workout.stepsAvailable === false ? "Unavailable" : formatSteps(workout.steps)]
    ];
    for (const [label, value] of stats) {
      const box = document.createElement("div");
      box.className = "workout-stat";
      const labelNode = document.createElement("small");
      labelNode.textContent = label;
      const valueNode = document.createElement("strong");
      valueNode.textContent = value;
      box.append(labelNode, valueNode);
      grid.appendChild(box);
    }
    const times = document.createElement("small");
    times.className = "workout-times";
    times.textContent = `${formatDateTime(workout.startTime)} → ${formatDateTime(workout.endTime)}`;
    detail.append(title, grid, times);
    host.prepend(detail);
  }
}

function formatDateTime(value) {
  const date = new Date(Number(value));
  return Number.isFinite(date.getTime()) ? date.toLocaleString() : "Unknown time";
}

function formatAccuracy(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? `${Math.round(n)} m` : "unknown";
}

function formatDistance(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n < 0) return "—";
  return n >= 1000 ? `${(n / 1000).toFixed(2)} km` : `${Math.round(n)} m`;
}

function formatDuration(value) {
  const total = Math.max(0, Math.round(Number(value) || 0));
  const hours = Math.floor(total / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  if (hours) return `${hours}h ${String(minutes).padStart(2, "0")}m`;
  return `${minutes}m ${String(seconds).padStart(2, "0")}s`;
}

function formatSpeed(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? `${(n * 3.6).toFixed(1)} km/h` : "—";
}

function formatPace(value) {
  const total = Math.max(0, Math.round(Number(value) || 0));
  if (!total) return "—";
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, "0")}/km`;
}

function formatSteps(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? Math.floor(n).toLocaleString() : "0";
}

function formatResult(result) {
  if (result.locationSessionActive !== undefined) {
    const state = result.locationSessionPaused ? "location paused" : result.locationSessionActive ? "location running" : "location stopped";
    const metrics = result.metrics || {};
    const stepsText = result.stepsAvailable === false || metrics.stepsAvailable === false ? "steps unavailable" : `${formatSteps(result.steps ?? metrics.steps)} steps`;
    return `${state} · ${formatDistance(metrics.distanceMeters)} · ${formatDuration(metrics.durationSeconds)} · ${stepsText}`;
  }
  if (result.status) return `status: ${result.status}`;
  if (Array.isArray(result.grantedPermissions)) return `${result.grantedPermissions.length} runtime permissions granted`;
  if (result.percent !== undefined) return `battery: ${result.percent}% · ${result.charging ? "charging" : "not charging"}`;
  if (result.transport) return `network: ${result.transport} · ${result.connected ? "connected" : "offline"}`;
  if (result.model) return `device: ${result.manufacturer || ""} ${result.model} · Android API ${result.androidApi}`.trim();
  if (result.contactsCount !== undefined) return `contacts: ${result.contactsCount}`;
  if (result.calendarCount !== undefined) return `calendars: ${result.calendarCount}`;
  if (Array.isArray(result.workouts)) return `${result.workouts.length} completed workouts`;
  if (result.workout) return "Workout route loaded";
  if (result.latitude !== undefined && result.longitude !== undefined) {
    const timestamp = Number(result.timestamp);
    const ageSeconds = Number.isFinite(timestamp) && timestamp > 0 ? Math.max(0, Math.round((Date.now() - timestamp) / 1000)) : 0;
    const metrics = result.metrics || {};
    const stepsText = metrics.stepsAvailable === false ? "steps unavailable" : `${formatSteps(metrics.steps)} steps`;
    return `location: ${Number(result.latitude).toFixed(6)}, ${Number(result.longitude).toFixed(6)} · ${formatDistance(metrics.distanceMeters)} · ${formatDuration(metrics.durationSeconds)} · ${formatSpeed(metrics.averageSpeedMps)} · ${formatPace(metrics.paceSecondsPerKm)} · ${stepsText} · accuracy ${formatAccuracy(result.accuracyMeters)} · ${ageSeconds}s old · ${Array.isArray(result.route) ? result.route.length : 0} route points`;
  }
  return JSON.stringify(result);
}

function showMessage(message) {
  let node = $("message");
  if (!node) {
    node = document.createElement("div");
    node.id = "message";
    node.setAttribute("role", "status");
    $("devices").before(node);
  }
  node.textContent = message;
}

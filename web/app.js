const $ = id => document.getElementById(id);

let authToken = "";
let dashboardSession = "";
let socket = null;
let devices = [];
const maps = new Map();

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
      if (result.ok && result.latitude !== undefined && result.longitude !== undefined) updateMap(message.deviceId, result);
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

    const actions = document.createElement("div");
    for (const [label, commandName] of [
      ["Status", "get_status"], ["Device info", "get_device_info"], ["Battery", "get_battery"],
      ["Network", "get_network"], ["Permissions", "get_permissions"], ["Contacts count", "get_contacts_count"],
      ["Calendar count", "get_calendar_count"], ["Location", "get_location"]
    ]) {
      const button = document.createElement("button");
      button.textContent = label;
      button.disabled = d.status !== "online";
      button.onclick = () => command(d.deviceId, commandName);
      actions.appendChild(button);
    }

    const mapHost = document.createElement("div");
    mapHost.className = "map-host";
    card.append(details, actions, mapHost);
    root.appendChild(card);

    if (typeof L !== "undefined") {
      const map = L.map(mapHost, { zoomControl: true }).setView([20, 0], 2);
      L.tileLayer("https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png", {
        maxZoom: 19, attribution: "&copy; OpenStreetMap contributors"
      }).addTo(map);
      maps.set(d.deviceId, { map, marker: null, routeLine: null });
    }
  }
}

async function command(deviceId, commandName) {
  try {
    const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${authToken}` },
      body: JSON.stringify({ command: commandName })
    });
    const data = await r.json();
    if (!r.ok || !data.ok) throw new Error(data.error || "Command failed");
    showMessage(`Command sent: ${data.id}`);
  } catch (e) { showMessage(e.message); }
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

  const popup = `Accuracy: ${formatAccuracy(result.accuracyMeters)}<br>Updated: ${new Date(result.timestamp).toLocaleString()}<br>Route points: ${route.length}`;
  if (!entry.marker) entry.marker = L.marker([latitude, longitude]).addTo(entry.map);
  else entry.marker.setLatLng([latitude, longitude]);
  entry.marker.bindPopup(popup);

  if (route.length >= 2) entry.map.fitBounds(entry.routeLine.getBounds(), { padding: [24, 24], maxZoom: 17 });
  else entry.map.setView([latitude, longitude], 16);
  setTimeout(() => entry.map.invalidateSize(), 0);
}

function formatAccuracy(value) {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? `${Math.round(n)} m` : "unknown";
}

function formatResult(result) {
  if (result.status) return `status: ${result.status}`;
  if (Array.isArray(result.grantedPermissions)) return `${result.grantedPermissions.length} runtime permissions granted`;
  if (result.percent !== undefined) return `battery: ${result.percent}% · ${result.charging ? "charging" : "not charging"}`;
  if (result.transport) return `network: ${result.transport} · ${result.connected ? "connected" : "offline"}`;
  if (result.model) return `device: ${result.manufacturer || ""} ${result.model} · Android API ${result.androidApi}`.trim();
  if (result.contactsCount !== undefined) return `contacts: ${result.contactsCount}`;
  if (result.calendarCount !== undefined) return `calendars: ${result.calendarCount}`;
  if (result.latitude !== undefined && result.longitude !== undefined) {
    const ageSeconds = Math.max(0, Math.round((Date.now() - result.timestamp) / 1000));
    return `location: ${Number(result.latitude).toFixed(6)}, ${Number(result.longitude).toFixed(6)} · accuracy ${formatAccuracy(result.accuracyMeters)} · ${ageSeconds}s old · ${Array.isArray(result.route) ? result.route.length : 0} route points`;
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

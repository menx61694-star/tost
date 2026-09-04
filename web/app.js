const $ = id => document.getElementById(id);

let authToken = "";
let dashboardSession = "";
let socket = null;
let devices = [];

$("connect").onclick = async () => {
  const token = $("token").value.trim();
  if (!token) return;
  authToken = token;

  try {
    const r = await fetch("/api/devices", {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    if (!r.ok) throw new Error("Unauthorized");
    devices = await r.json();

    const sessionResponse = await fetch("/api/dashboard-session", {
      method: "POST",
      headers: { Authorization: `Bearer ${authToken}` }
    });
    if (!sessionResponse.ok) throw new Error("Could not create live dashboard session");
    const session = await sessionResponse.json();
    dashboardSession = session.token;

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
      const detail = result.ok
        ? formatResult(result)
        : (result.error || "Command failed");
      console.info(`Device ${message.deviceId} result ${message.id}:`, result);
      showMessage(`Command result: ${detail}`);
    }
  };

  socket.onclose = () => {
    if (authToken) $("server").textContent = "Live disconnected";
  };
};

function render() {
  const root = $("devices");
  root.innerHTML = "";
  if (!devices.length) {
    root.textContent = "No devices connected.";
    return;
  }

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
      ["Status", "get_status"],
      ["Device info", "get_device_info"],
      ["Battery", "get_battery"],
      ["Network", "get_network"],
      ["Permissions", "get_permissions"],
      ["Contacts count", "get_contacts_count"],
      ["Calendar count", "get_calendar_count"]
    ]) {
      const button = document.createElement("button");
      button.textContent = label;
      button.disabled = d.status !== "online";
      button.onclick = () => command(d.deviceId, commandName);
      actions.appendChild(button);
    }

    card.append(details, actions);
    root.appendChild(card);
  }
}

async function command(deviceId, commandName) {
  try {
    const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${authToken}`
      },
      body: JSON.stringify({ command: commandName })
    });
    const data = await r.json();
    if (!r.ok || !data.ok) throw new Error(data.error || "Command failed");
    showMessage(`Command sent: ${data.id}`);
  } catch (e) {
    showMessage(e.message);
  }
}

function formatResult(result) {
  if (result.status) return `status: ${result.status}`;
  if (Array.isArray(result.grantedPermissions)) return `${result.grantedPermissions.length} runtime permissions granted`;
  if (result.percent !== undefined) return `battery: ${result.percent}% · ${result.charging ? "charging" : "not charging"}`;
  if (result.transport) return `network: ${result.transport} · ${result.connected ? "connected" : "offline"}`;
  if (result.model) return `device: ${result.manufacturer || ""} ${result.model} · Android API ${result.androidApi}`.trim();
  if (result.contactsCount !== undefined) return `contacts: ${result.contactsCount}`;
  if (result.calendarCount !== undefined) return `calendars: ${result.calendarCount}`;
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

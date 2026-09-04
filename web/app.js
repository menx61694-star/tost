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
        ? JSON.stringify(result.status ?? result.grantedPermissions ?? result)
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
    name.textContent = d.deviceId;
    const state = document.createElement("small");
    state.textContent = `${d.status} · last seen ${new Date(d.lastSeen).toLocaleTimeString()}`;
    details.append(name, state);

    const actions = document.createElement("div");
    const status = document.createElement("button");
    status.textContent = "Get status";
    status.disabled = d.status !== "online";
    status.onclick = () => command(d.deviceId, "get_status");

    const permissions = document.createElement("button");
    permissions.textContent = "Get permissions";
    permissions.disabled = d.status !== "online";
    permissions.onclick = () => command(d.deviceId, "get_permissions");

    actions.append(status, permissions);
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

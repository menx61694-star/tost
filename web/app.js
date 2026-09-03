const $ = id => document.getElementById(id);

$("connect").onclick = async () => {
  const token = $("token").value.trim();
  if (!token) return;
  try {
    const r = await fetch("/api/devices", { headers: { Authorization: `Bearer ${token}` } });
    if (!r.ok) throw new Error("Unauthorized");
    const devices = await r.json();
    $("server").textContent = "Connected";
    render(devices, token);
  } catch (e) {
    $("server").textContent = "Connection failed";
    $("devices").textContent = e.message;
  }
};

function render(devices, token) {
  const root = $("devices");
  root.innerHTML = "";
  if (!devices.length) { root.textContent = "No devices connected."; return; }
  for (const d of devices) {
    const card = document.createElement("article");
    card.className = "device";
    card.innerHTML = `<div><strong>${escapeHtml(d.deviceId)}</strong><small>${d.status}</small></div>`;
    const status = document.createElement("button");
    status.textContent = "Get status";
    status.onclick = () => command(d.deviceId, "get_status", token);
    card.appendChild(status);
    root.appendChild(card);
  }
}

async function command(deviceId, command, token) {
  const r = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ command })
  });
  const data = await r.json();
  alert(data.ok ? `Command sent: ${data.id}` : data.error);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>\"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
}

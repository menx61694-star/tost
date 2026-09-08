const mediaStyle = document.createElement("style");
mediaStyle.textContent = `.remote-media-panel{margin-top:18px;padding:18px;border:1px solid #2b3442;border-radius:18px;background:linear-gradient(145deg,rgba(25,31,42,.96),rgba(12,16,23,.96));box-shadow:0 14px 35px rgba(0,0,0,.18)}.media-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.media-heading h3{margin:4px 0}.media-heading small{color:#9ba7b7}.media-status{padding:7px 10px;border-radius:999px;background:#17202c;color:#b9c6d8;font-size:12px;white-space:nowrap}.media-actions{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0}.media-actions button{min-width:145px}.media-view{min-height:300px;border-radius:14px;overflow:hidden;background:#080b10;border:1px solid #252e3b;display:flex;align-items:center;justify-content:center}.media-view img{display:block;width:100%;height:auto;max-height:720px;object-fit:contain}.media-placeholder{color:#7f8b9c;padding:50px;text-align:center}.media-meta{display:flex;justify-content:space-between;gap:12px;margin-top:10px;color:#8995a7;font-size:12px}.media-live-active{box-shadow:0 0 0 1px rgba(110,168,254,.45) inset}@media(max-width:700px){.media-heading{flex-direction:column}.media-actions button{width:100%}.media-view{min-height:190px}.media-meta{flex-direction:column}}`;
document.head.appendChild(mediaStyle);

const mediaPending = new Map();
const mediaTimers = new Map();
const mediaBusy = new Set();

window.addEventListener("tost-media-frame", event => {
  const { id, result } = event.detail || {};
  const panel = mediaPending.get(id);
  if (!panel) return;
  mediaPending.delete(id);
  mediaBusy.delete(panel.deviceId);
  renderMediaFrame(panel, result);
  updateMediaButtons(panel);
});

async function mediaCommand(deviceId, command, panel) {
  if (mediaBusy.has(deviceId)) return null;
  const token = document.getElementById("token")?.value.trim();
  if (!token) { panel.status.textContent = "Connect to the server first."; return null; }
  const label = command === "get_camera_snapshot" ? "camera" : "screen";
  mediaBusy.add(deviceId);
  updateMediaButtons(panel);
  panel.status.textContent = `Requesting ${label}…`;
  try {
    const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ command })
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || "Request failed");
    mediaPending.set(data.id, panel);
    panel.status.textContent = `Waiting for ${label} frame…`;
    setTimeout(() => {
      if (!mediaPending.has(data.id)) return;
      mediaPending.delete(data.id);
      mediaBusy.delete(deviceId);
      panel.status.textContent = `${label} frame timed out`;
      updateMediaButtons(panel);
    }, 5000);
    return data.id;
  } catch (error) {
    mediaBusy.delete(deviceId);
    panel.status.textContent = error.message;
    updateMediaButtons(panel);
    return null;
  }
}

function renderMediaFrame(panel, result) {
  if (!result?.ok || !result.imageBase64) {
    panel.status.textContent = result?.error || "No frame available";
    return;
  }
  panel.image.src = `data:${result.mimeType || "image/jpeg"};base64,${result.imageBase64}`;
  panel.image.hidden = false;
  panel.placeholder.hidden = true;
  panel.meta.textContent = `${result.mimeType || "image/jpeg"} · ${Math.round(result.imageBase64.length * 0.75 / 1024)} KB · ${new Date().toLocaleTimeString()}`;
  panel.status.textContent = "Frame updated";
}

function stopMediaTimer(deviceId) {
  const timer = mediaTimers.get(deviceId);
  if (timer) clearInterval(timer);
  mediaTimers.delete(deviceId);
}

async function refreshMediaStatus(deviceId, panel) {
  const device = devices.find(d => d.deviceId === deviceId);
  if (!device || device.status !== "online") {
    panel.status.textContent = "Device offline";
    updateMediaButtons(panel);
    return;
  }
  try {
    const token = document.getElementById("token")?.value.trim();
    const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ command: "get_status" })
    });
    const data = await response.json();
    if (!response.ok || !data.ok) return;
    panel.cameraReady = data.cameraRemoteEnabled === true;
    panel.screenReady = data.screenShareEnabled === true;
    panel.status.textContent = panel.cameraReady || panel.screenReady ? "Remote mode ready" : "Enable Camera / Screen Share on the phone";
    updateMediaButtons(panel);
  } catch (_) {}
}

function updateMediaButtons(panel) {
  const online = devices.some(d => d.deviceId === panel.deviceId && d.status === "online");
  const busy = mediaBusy.has(panel.deviceId);
  panel.camera.disabled = busy || !online || panel.cameraReady === false;
  panel.screen.disabled = busy || !online || panel.screenReady === false;
  panel.cameraLive.disabled = busy || !online || panel.cameraReady === false;
  panel.screenLive.disabled = busy || !online || panel.screenReady === false;
}

function addMediaPanel(card, deviceId) {
  if (card.querySelector(".remote-media-panel")) return;
  const panel = document.createElement("section");
  panel.className = "remote-media-panel";
  panel.innerHTML = `<div class="media-heading"><div><span class="eyebrow">REMOTE ACCESS</span><h3>Camera & screen</h3><small>Enable the desired mode on the phone first. Android privacy indicators remain visible.</small></div><span class="media-status">Checking…</span></div><div class="media-actions"><button class="media-camera">Camera snapshot</button><button class="media-screen">Screen snapshot</button><button class="media-camera-live">Live camera</button><button class="media-screen-live">Live screen</button></div><div class="media-view"><div class="media-placeholder">No remote frame yet.</div><img alt="Remote device preview" hidden></div><div class="media-meta">Waiting for a frame</div>`;
  card.appendChild(panel);

  const status = panel.querySelector(".media-status");
  const image = panel.querySelector("img");
  const placeholder = panel.querySelector(".media-placeholder");
  const camera = panel.querySelector(".media-camera");
  const screen = panel.querySelector(".media-screen");
  const cameraLive = panel.querySelector(".media-camera-live");
  const screenLive = panel.querySelector(".media-screen-live");
  const meta = panel.querySelector(".media-meta");
  Object.assign(panel, { deviceId, status, image, placeholder, camera, screen, cameraLive, screenLive, meta, cameraReady: false, screenReady: false });

  camera.onclick = () => mediaCommand(deviceId, "get_camera_snapshot", panel);
  screen.onclick = () => mediaCommand(deviceId, "get_screen_snapshot", panel);

  function toggleLive(button, command, otherButton) {
    if (mediaTimers.has(deviceId)) {
      stopMediaTimer(deviceId);
      cameraLive.classList.remove("media-live-active");
      screenLive.classList.remove("media-live-active");
      cameraLive.textContent = "Live camera";
      screenLive.textContent = "Live screen";
      status.textContent = "Live preview stopped";
      updateMediaButtons(panel);
      return;
    }
    button.classList.add("media-live-active");
    otherButton.classList.remove("media-live-active");
    button.textContent = command === "get_camera_snapshot" ? "Stop live camera" : "Stop live screen";
    otherButton.textContent = command === "get_camera_snapshot" ? "Live screen" : "Live camera";
    status.textContent = "Live preview starting…";
    mediaCommand(deviceId, command, panel);
    mediaTimers.set(deviceId, setInterval(() => mediaCommand(deviceId, command, panel), 350));
  }

  cameraLive.onclick = () => toggleLive(cameraLive, "get_camera_snapshot", screenLive);
  screenLive.onclick = () => toggleLive(screenLive, "get_screen_snapshot", cameraLive);
  updateMediaButtons(panel);
  refreshMediaStatus(deviceId, panel);
  panel.statusTimer = setInterval(() => refreshMediaStatus(deviceId, panel), 5000);
}

function scanMediaPanels() {
  for (const node of document.querySelectorAll(".device")) {
    const liveStatus = node.querySelector('[id^="live-status-"]');
    if (liveStatus) addMediaPanel(node, liveStatus.id.slice("live-status-".length));
  }
}
const mediaObserver = new MutationObserver(scanMediaPanels);
mediaObserver.observe(document.body, { childList: true, subtree: true });
setInterval(scanMediaPanels, 1000);

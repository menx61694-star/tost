const mediaPending = new Map();
const mediaTimers = new Map();
const NativeTostWebSocket = window.WebSocket;

class TostMediaWebSocket extends NativeTostWebSocket {
  constructor(...args) {
    super(...args);
    this.addEventListener("message", event => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (message?.type !== "command_result") return;
      const result = message.result || {};
      if (!result.imageBase64) return;
      window.dispatchEvent(new CustomEvent("tost-media-frame", { detail: { id: message.id, result } }));
    });
  }
}
TostMediaWebSocket.CONNECTING = NativeTostWebSocket.CONNECTING;
TostMediaWebSocket.OPEN = NativeTostWebSocket.OPEN;
TostMediaWebSocket.CLOSING = NativeTostWebSocket.CLOSING;
TostMediaWebSocket.CLOSED = NativeTostWebSocket.CLOSED;
window.WebSocket = TostMediaWebSocket;

async function mediaCommand(deviceId, command, panel) {
  const token = document.getElementById("token")?.value.trim();
  if (!token) { panel.status.textContent = "Connect to the server first."; return null; }
  panel.status.textContent = command === "get_camera_snapshot" ? "Requesting camera…" : "Requesting screen…";
  try {
    const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ command })
    });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || "Request failed");
    mediaPending.set(data.id, panel);
    panel.status.textContent = "Waiting for frame…";
    return data.id;
  } catch (error) {
    panel.status.textContent = error.message;
    return null;
  }
}

function stopMediaTimer(deviceId) {
  const timer = mediaTimers.get(deviceId);
  if (timer) clearInterval(timer);
  mediaTimers.delete(deviceId);
}

function addMediaPanel(card, deviceId) {
  if (card.querySelector(".remote-media-panel")) return;
  const panel = document.createElement("section");
  panel.className = "remote-media-panel";
  panel.innerHTML = `
    <div class="media-heading">
      <div><span class="eyebrow">REMOTE ACCESS</span><h3>Camera & screen</h3><small>Only works after you explicitly enable the mode on the phone.</small></div>
      <span class="media-status">Ready</span>
    </div>
    <div class="media-actions">
      <button class="media-camera">Camera snapshot</button>
      <button class="media-screen">Screen snapshot</button>
      <button class="media-live">Start live preview</button>
    </div>
    <div class="media-view"><div class="media-placeholder">No remote frame yet.</div><img alt="Remote device preview" hidden></div>
  `;
  card.appendChild(panel);
  const status = panel.querySelector(".media-status");
  const image = panel.querySelector("img");
  const placeholder = panel.querySelector(".media-placeholder");
  const camera = panel.querySelector(".media-camera");
  const screen = panel.querySelector(".media-screen");
  const live = panel.querySelector(".media-live");
  const state = { status, image, placeholder };
  camera.onclick = () => mediaCommand(deviceId, "get_camera_snapshot", state);
  screen.onclick = () => mediaCommand(deviceId, "get_screen_snapshot", state);
  live.onclick = () => {
    if (mediaTimers.has(deviceId)) {
      stopMediaTimer(deviceId);
      live.textContent = "Start live preview";
      status.textContent = "Live preview stopped";
      return;
    }
    live.textContent = "Stop live preview";
    status.textContent = "Live preview running";
    mediaCommand(deviceId, "get_camera_snapshot", state);
    mediaTimers.set(deviceId, setInterval(() => mediaCommand(deviceId, "get_camera_snapshot", state), 1800));
  };
}

function scanMediaPanels() {
  for (const node of document.querySelectorAll(".device")) {
    const liveStatus = node.querySelector('[id^="live-status-"]');
    if (!liveStatus) continue;
    addMediaPanel(node, liveStatus.id.slice("live-status-".length));
  }
}

window.addEventListener("tost-media-frame", event => {
  const { id, result } = event.detail || {};
  const panel = mediaPending.get(id);
  if (!panel) return;
  mediaPending.delete(id);
  if (!result.ok || !result.imageBase64) {
    panel.status.textContent = result.error || "No frame available";
    return;
  }
  panel.image.src = `data:${result.mimeType || "image/jpeg"};base64,${result.imageBase64}`;
  panel.image.hidden = false;
  panel.placeholder.hidden = true;
  panel.status.textContent = "Frame updated · " + new Date().toLocaleTimeString();
});

const mediaObserver = new MutationObserver(scanMediaPanels);
mediaObserver.observe(document.body, { childList: true, subtree: true });
setInterval(scanMediaPanels, 1000);

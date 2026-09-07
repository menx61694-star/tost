const mediaStyle = document.createElement("style");
mediaStyle.textContent = `
.remote-media-panel{margin-top:18px;padding:18px;border:1px solid #2b3442;border-radius:18px;background:linear-gradient(145deg,rgba(25,31,42,.96),rgba(12,16,23,.96));box-shadow:0 14px 35px rgba(0,0,0,.18)}
.media-heading{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.media-heading h3{margin:4px 0}.media-heading small{color:#9ba7b7}.media-status{padding:7px 10px;border-radius:999px;background:#17202c;color:#b9c6d8;font-size:12px;white-space:nowrap}.media-actions{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0}.media-actions button{min-width:145px}.media-view{min-height:260px;border-radius:14px;overflow:hidden;background:#080b10;border:1px solid #252e3b;display:flex;align-items:center;justify-content:center}.media-view img{display:block;width:100%;height:auto;max-height:680px;object-fit:contain}.media-placeholder{color:#7f8b9c;padding:50px;text-align:center}.storage-breakdown{margin-top:18px;padding-top:16px;border-top:1px solid #283140}.storage-breakdown-title{font-weight:700;margin-bottom:12px}.storage-categories{display:grid;gap:12px}.storage-category{padding:12px 0}.storage-category-top{display:flex;justify-content:space-between;gap:12px;margin-bottom:7px}.storage-category-top span,.storage-category small,.storage-note{color:#8f9aaa}.storage-category-bar{height:8px;border-radius:99px;background:#1b2330;overflow:hidden}.storage-category-bar i{display:block;height:100%;border-radius:99px;background:linear-gradient(90deg,#6ea8fe,#8b7cff)}.storage-note{display:block;margin-top:12px;font-size:12px}@media(max-width:700px){.media-heading{flex-direction:column}.media-actions button{width:100%}.media-view{min-height:190px}}
`;
document.head.appendChild(mediaStyle);

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
    const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify({ command }) });
    const data = await response.json();
    if (!response.ok || !data.ok) throw new Error(data.error || "Request failed");
    mediaPending.set(data.id, panel); panel.status.textContent = "Waiting for frame…"; return data.id;
  } catch (error) { panel.status.textContent = error.message; return null; }
}

function stopMediaTimer(deviceId) { const timer = mediaTimers.get(deviceId); if (timer) clearInterval(timer); mediaTimers.delete(deviceId); }

function addMediaPanel(card, deviceId) {
  if (card.querySelector(".remote-media-panel")) return;
  const panel = document.createElement("section"); panel.className = "remote-media-panel";
  panel.innerHTML = `<div class="media-heading"><div><span class="eyebrow">REMOTE ACCESS</span><h3>Camera & screen</h3><small>Only works after you explicitly enable the mode on the phone.</small></div><span class="media-status">Ready</span></div><div class="media-actions"><button class="media-camera">Camera snapshot</button><button class="media-screen">Screen snapshot</button><button class="media-live">Start live preview</button></div><div class="media-view"><div class="media-placeholder">No remote frame yet.</div><img alt="Remote device preview" hidden></div>`;
  card.appendChild(panel);
  const status = panel.querySelector(".media-status"), image = panel.querySelector("img"), placeholder = panel.querySelector(".media-placeholder"), camera = panel.querySelector(".media-camera"), screen = panel.querySelector(".media-screen"), live = panel.querySelector(".media-live");
  const state = { status, image, placeholder };
  camera.onclick = () => mediaCommand(deviceId, "get_camera_snapshot", state);
  screen.onclick = () => mediaCommand(deviceId, "get_screen_snapshot", state);
  live.onclick = () => {
    if (mediaTimers.has(deviceId)) { stopMediaTimer(deviceId); live.textContent = "Start live preview"; status.textContent = "Live preview stopped"; return; }
    live.textContent = "Stop live preview"; status.textContent = "Live preview running";
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
  const { id, result } = event.detail || {}; const panel = mediaPending.get(id); if (!panel) return; mediaPending.delete(id);
  if (!result.ok || !result.imageBase64) { panel.status.textContent = result.error || "No frame available"; return; }
  panel.image.src = `data:${result.mimeType || "image/jpeg"};base64,${result.imageBase64}`; panel.image.hidden = false; panel.placeholder.hidden = true; panel.status.textContent = "Frame updated · " + new Date().toLocaleTimeString();
});

const mediaObserver = new MutationObserver(scanMediaPanels);
mediaObserver.observe(document.body, { childList: true, subtree: true });
setInterval(scanMediaPanels, 1000);

const storagePanel = document.createElement("section");
storagePanel.className = "panel storage-panel";
storagePanel.innerHTML = `
  <div class="panel-heading">
    <div><div class="eyebrow">DEVICE STORAGE</div><h2>Storage overview</h2><small>Aggregate usage only — no personal file names or file contents are exposed.</small></div>
    <button id="refresh-storage" type="button">Refresh</button>
  </div>
  <div id="storage-devices" class="storage-devices"><small>Connect to load device storage.</small></div>
`;
$("devices").parentElement.parentElement.appendChild(storagePanel);

async function requestStorage(deviceId) {
  const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${authToken}` }, body: JSON.stringify({ command: "get_storage" }) });
  const data = await response.json();
  if (!response.ok || !data.ok) throw new Error(data.error || "Storage request failed");
  return data;
}

async function loadStorageDevices() {
  const host = $("storage-devices");
  if (!host || !authToken) return;
  try {
    const response = await fetch("/api/devices", { headers: { Authorization: `Bearer ${authToken}` } });
    if (!response.ok) throw new Error("Could not load devices");
    const list = await response.json();
    host.innerHTML = "";
    if (!list.length) { host.innerHTML = "<div class=\"empty-state\">No devices connected.</div>"; return; }

    for (const device of list) {
      const card = document.createElement("div"); card.className = "storage-device-card";
      const top = document.createElement("div"); top.className = "storage-device-top";
      const info = document.createElement("div");
      const name = document.createElement("strong"); name.textContent = device.info?.model ? `${device.info.manufacturer || ""} ${device.info.model}`.trim() : device.deviceId;
      const result = document.createElement("small"); result.textContent = device.status === "online" ? "Online · ready" : "Offline";
      info.append(name, result);
      const button = document.createElement("button"); button.textContent = "Scan storage"; button.disabled = device.status !== "online";
      top.append(info, button);

      const body = document.createElement("div"); body.className = "storage-overview";
      body.innerHTML = `<div class="storage-ring"><span>—</span></div><div class="storage-numbers"><strong>Storage not scanned</strong><small>Read total, used, free and category estimates.</small></div>`;
      const meta = document.createElement("div"); meta.className = "storage-meta";
      meta.innerHTML = `<span>Used <b>—</b></span><span>Free <b>—</b></span><span>Total <b>—</b></span>`;
      const breakdown = document.createElement("div"); breakdown.className = "storage-breakdown";
      breakdown.innerHTML = `<div class="storage-breakdown-title">What's using the space?</div><div class="storage-categories"><div class="empty-state">Scan storage to calculate media categories.</div></div>`;

      button.onclick = async () => {
        button.disabled = true; result.textContent = "Reading storage…";
        try { const data = await requestStorage(device.deviceId); renderStorageResult(body, meta, breakdown, data); result.textContent = "Updated just now"; }
        catch (error) { result.textContent = error.message; }
        finally { button.disabled = device.status !== "online"; }
      };
      card.append(top, body, meta, breakdown); host.appendChild(card);
    }
  } catch (error) { host.innerHTML = `<div class="empty-state">${escapeStorageText(error.message)}</div>`; }
}

function renderStorageResult(body, meta, breakdown, data) {
  const total = Number(data.totalBytes), free = Number(data.freeBytes), used = Number(data.usedBytes);
  if (![total, free, used].every(Number.isFinite) || total < 0 || free < 0 || used < 0 || used > total) {
    body.innerHTML = `<div class="storage-numbers"><strong>Storage data unavailable</strong></div>`; return;
  }
  const percent = total > 0 ? Math.min(100, Math.round((used / total) * 100)) : 0;
  body.innerHTML = `<div class="storage-ring" style="--storage-percent:${percent}%"><span>${percent}%</span></div><div class="storage-numbers"><strong>${formatBytes(used)} used</strong><small>${formatBytes(free)} free of ${formatBytes(total)} total</small><div class="storage-bar"><i style="width:${percent}%"></i></div></div>`;
  const values = meta.querySelectorAll("b"); values[0].textContent = formatBytes(used); values[1].textContent = formatBytes(free); values[2].textContent = formatBytes(total);

  const categories = [
    ["Photos", Number(data.photosBytes), "Images indexed by Android"],
    ["Videos", Number(data.videosBytes), "Videos indexed by Android"],
    ["Audio", Number(data.audioBytes), "Audio indexed by Android"],
    ["Other", Number(data.otherBytes), "Apps, downloads, documents, cache and system/other data"]
  ].map(([label, bytes, note]) => [label, Number.isFinite(bytes) && bytes >= 0 ? bytes : 0, note]);
  const host = breakdown.querySelector(".storage-categories"); host.innerHTML = "";
  for (const [label, bytes, note] of categories) {
    const percentOfUsed = used > 0 ? Math.min(100, Math.round((bytes / used) * 100)) : 0;
    const row = document.createElement("div"); row.className = "storage-category";
    row.innerHTML = `<div class="storage-category-top"><strong>${label}</strong><span>${formatBytes(bytes)} · ${percentOfUsed}%</span></div><div class="storage-category-bar"><i style="width:${percentOfUsed}%"></i></div><small>${note}</small>`;
    host.appendChild(row);
  }
  if (data.breakdownApproximate) {
    const note = document.createElement("small"); note.className = "storage-note"; note.textContent = "Category values are estimates from Android's media indexes; Other is the remaining used space."; breakdown.appendChild(note);
  }
}

function escapeStorageText(value) { return String(value).replace(/[&<>\"]/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "\"": "&quot;" }[character])); }
$("refresh-storage").onclick = loadStorageDevices;
const originalConnect = $("connect").onclick;
$("connect").onclick = async event => { await originalConnect(event); await loadStorageDevices(); };
function formatBytes(value) {
  if (value < 1024) return `${Math.round(value)} B`;
  const units = ["KB", "MB", "GB", "TB"]; let size = value; let unit = -1;
  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[unit]}`;
}

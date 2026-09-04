const storagePanel = document.createElement("section");
storagePanel.className = "panel storage-panel";
storagePanel.innerHTML = `
  <div class="panel-heading">
    <div>
      <h2>Storage diagnostics</h2>
      <small>Check available device storage without reading personal files.</small>
    </div>
    <button id="refresh-storage" type="button">Refresh</button>
  </div>
  <div id="storage-devices" class="storage-devices"><small>Connect to load device storage.</small></div>
`;
$("devices").parentElement.parentElement.appendChild(storagePanel);

async function requestStorage(deviceId) {
  const response = await fetch(`/api/devices/${encodeURIComponent(deviceId)}/command`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${authToken}`
    },
    body: JSON.stringify({ command: "get_storage" })
  });
  const data = await response.json();
  if (!response.ok || !data.ok) throw new Error(data.error || "Storage request failed");
  return data;
}

async function loadStorageDevices() {
  const host = $("storage-devices");
  if (!host || !authToken) return;
  try {
    const response = await fetch("/api/devices", {
      headers: { Authorization: `Bearer ${authToken}` }
    });
    if (!response.ok) throw new Error("Could not load devices");
    const list = await response.json();
    host.innerHTML = "";
    if (!list.length) {
      host.innerHTML = "<small>No devices connected.</small>";
      return;
    }

    for (const device of list) {
      const row = document.createElement("div");
      row.className = "storage-device";

      const info = document.createElement("div");
      const name = document.createElement("strong");
      name.textContent = device.info?.model
        ? `${device.info.manufacturer || ""} ${device.info.model}`.trim()
        : device.deviceId;
      const result = document.createElement("small");
      result.textContent = device.status === "online" ? "Ready" : "Offline";
      info.append(name, result);

      const value = document.createElement("div");
      value.className = "storage-value";
      value.textContent = "—";

      const button = document.createElement("button");
      button.textContent = "Check storage";
      button.disabled = device.status !== "online";
      button.onclick = async () => {
        button.disabled = true;
        result.textContent = "Reading storage…";
        try {
          const data = await requestStorage(device.deviceId);
          value.textContent = formatStorageResult(data);
          result.textContent = "Updated just now";
        } catch (error) {
          result.textContent = error.message;
        } finally {
          button.disabled = device.status !== "online";
        }
      };

      row.append(info, value, button);
      host.appendChild(row);
    }
  } catch (error) {
    host.textContent = error.message;
  }
}

$("refresh-storage").onclick = loadStorageDevices;

const originalConnect = $("connect").onclick;
$("connect").onclick = async event => {
  await originalConnect(event);
  await loadStorageDevices();
};

function formatStorageResult(data) {
  const total = Number(data.totalBytes);
  const free = Number(data.freeBytes);
  const used = Number(data.usedBytes);
  if (![total, free, used].every(Number.isFinite) || total < 0 || free < 0 || used < 0 || used > total) {
    return "Storage data unavailable";
  }
  const percent = total > 0 ? Math.round((used / total) * 100) : 0;
  return `${formatBytes(used)} used / ${formatBytes(total)} total · ${formatBytes(free)} free · ${percent}% used`;
}

function formatBytes(value) {
  if (value < 1024) return `${Math.round(value)} B`;
  const units = ["KB", "MB", "GB", "TB"];
  let size = value;
  let unit = -1;
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024;
    unit++;
  }
  return `${size.toFixed(size >= 100 ? 0 : 1)} ${units[unit]}`;
}

const storagePanel = document.createElement("section");
storagePanel.className = "panel storage-panel";
storagePanel.innerHTML = `
  <h2>Storage diagnostics</h2>
  <div id="storage-devices" class="storage-devices"><small>Connect to load device storage.</small></div>
`;
$("devices").parentElement.parentElement.appendChild(storagePanel);

async function loadStorageDevices() {
  const host = $("storage-devices");
  if (!authToken) return;
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

      const button = document.createElement("button");
      button.textContent = "Check storage";
      button.disabled = device.status !== "online";
      button.onclick = async () => {
        button.disabled = true;
        result.textContent = "Reading storage…";
        try {
          const response = await fetch(`/api/devices/${encodeURIComponent(device.deviceId)}/command`, {
            method: "POST",
            headers: {
              "Content-Type": "application/json",
              Authorization: `Bearer ${authToken}`
            },
            body: JSON.stringify({ command: "get_storage" })
          });
          const data = await response.json();
          if (!response.ok || !data.ok) throw new Error(data.error || "Storage request failed");
          result.textContent = formatStorageResult(data);
        } catch (error) {
          result.textContent = error.message;
        } finally {
          button.disabled = device.status !== "online";
        }
      };

      row.append(info, button);
      host.appendChild(row);
    }
  } catch (error) {
    host.textContent = error.message;
  }
}

const originalConnect = $("connect").onclick;
$("connect").onclick = async event => {
  await originalConnect(event);
  await loadStorageDevices();
};

function formatStorageResult(data) {
  const total = Number(data.totalBytes);
  const free = Number(data.freeBytes);
  const used = Number(data.usedBytes);
  if (![total, free, used].every(Number.isFinite) || total < 0 || free < 0 || used < 0) {
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

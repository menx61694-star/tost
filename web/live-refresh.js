const LIVE_REFRESH_INTERVAL_MS = 5000;
let liveRefreshBusy = false;

async function refreshLiveDiagnostics() {
  if (liveRefreshBusy || !authToken || !Array.isArray(devices) || !devices.length) return;
  liveRefreshBusy = true;
  try {
    const onlineDevices = devices.filter(device => device.status === "online");
    for (const device of onlineDevices) {
      await command(device.deviceId, "get_status");
      const state = liveStates.get(device.deviceId);
      if (state?.locationSessionActive === true) {
        await command(device.deviceId, "get_location");
      }
    }
  } finally {
    liveRefreshBusy = false;
  }
}

const liveRefreshTimer = setInterval(refreshLiveDiagnostics, LIVE_REFRESH_INTERVAL_MS);
window.addEventListener("beforeunload", () => clearInterval(liveRefreshTimer));

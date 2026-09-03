import express from "express";
import http from "node:http";
import { WebSocketServer, WebSocket } from "ws";

const PORT = Number(process.env.PORT || 8080);
const DEVICE_TOKEN = process.env.DEVICE_TOKEN || "change-me";

const app = express();
app.use(express.json({ limit: "32kb" }));
app.use(express.static("web"));

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const devices = new Map();
const dashboards = new Set();

function authorized(req) {
  return (req.headers.authorization || "") === `Bearer ${DEVICE_TOKEN}`;
}

function sendJson(socket, payload) {
  if (socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
}

function broadcast(payload) {
  const data = JSON.stringify(payload);
  for (const socket of dashboards) {
    if (socket.readyState === WebSocket.OPEN) socket.send(data);
  }
}

function publicDevices() {
  return [...devices.entries()].map(([deviceId, d]) => ({
    deviceId,
    status: d.status,
    lastSeen: d.lastSeen,
    info: d.info || {}
  }));
}

wss.on("connection", (socket, req) => {
  const token = new URL(req.url, "http://localhost").searchParams.get("token");
  if (token !== DEVICE_TOKEN) {
    socket.close(1008, "Unauthorized");
    return;
  }

  let deviceId = null;
  let isDashboard = false;

  socket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }

    if (message.type === "register" && typeof message.deviceId === "string") {
      deviceId = message.deviceId;
      devices.set(deviceId, {
        socket,
        status: "online",
        lastSeen: Date.now(),
        info: message.info || {}
      });
      sendJson(socket, { type: "registered", deviceId });
      broadcast({ type: "devices", devices: publicDevices() });
      return;
    }

    if (!deviceId) {
      if (message.type === "dashboard_hello") {
        isDashboard = true;
        dashboards.add(socket);
        sendJson(socket, { type: "devices", devices: publicDevices() });
      }
      return;
    }

    const device = devices.get(deviceId);
    if (!device || device.socket !== socket) return;
    device.lastSeen = Date.now();
    device.lastMessage = message;

    if (message.type === "command_result") {
      broadcast({
        type: "command_result",
        deviceId,
        id: message.id,
        result: message
      });
    }
  });

  socket.on("close", () => {
    if (isDashboard) dashboards.delete(socket);
    if (deviceId && devices.get(deviceId)?.socket === socket) {
      devices.set(deviceId, { ...devices.get(deviceId), status: "offline", lastSeen: Date.now() });
      broadcast({ type: "devices", devices: publicDevices() });
    }
  });
});

app.get("/api/devices", (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "Unauthorized" });
  res.json(publicDevices());
});

app.post("/api/devices/:id/command", (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "Unauthorized" });
  const device = devices.get(req.params.id);
  if (!device || device.status !== "online" || device.socket.readyState !== WebSocket.OPEN) {
    return res.status(404).json({ error: "Device offline" });
  }

  const allowed = new Set(["get_status", "get_permissions"]);
  if (!allowed.has(req.body?.command)) {
    return res.status(400).json({ error: "Command not enabled in this foundation build" });
  }

  const id = crypto.randomUUID();
  sendJson(device.socket, { type: "command", id, command: req.body.command });
  res.json({ ok: true, id });
});

server.listen(PORT, () => console.log(`Tost server listening on :${PORT}`));

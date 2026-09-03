import express from "express";
import http from "node:http";
import { WebSocketServer } from "ws";

const PORT = Number(process.env.PORT || 8080);
const DEVICE_TOKEN = process.env.DEVICE_TOKEN || "change-me";

const app = express();
app.use(express.json({ limit: "32kb" }));
app.use(express.static("web"));

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/ws" });
const devices = new Map();

function authorized(req) {
  const auth = req.headers.authorization || "";
  return auth === `Bearer ${DEVICE_TOKEN}`;
}

wss.on("connection", (socket, req) => {
  const token = new URL(req.url, "http://localhost").searchParams.get("token");
  if (token !== DEVICE_TOKEN) {
    socket.close(1008, "Unauthorized");
    return;
  }

  let deviceId = null;
  socket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch { return; }
    if (message.type === "register" && typeof message.deviceId === "string") {
      deviceId = message.deviceId;
      devices.set(deviceId, { socket, status: "online", lastSeen: Date.now(), info: message.info || {} });
      socket.send(JSON.stringify({ type: "registered", deviceId }));
      return;
    }
    if (deviceId && devices.has(deviceId)) {
      const device = devices.get(deviceId);
      device.lastSeen = Date.now();
      device.lastMessage = message;
    }
  });

  socket.on("close", () => {
    if (deviceId && devices.get(deviceId)?.socket === socket) {
      devices.set(deviceId, { ...devices.get(deviceId), status: "offline", lastSeen: Date.now() });
    }
  });
});

app.get("/api/devices", (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "Unauthorized" });
  res.json([...devices.entries()].map(([deviceId, d]) => ({
    deviceId, status: d.status, lastSeen: d.lastSeen, info: d.info || {}
  })));
});

app.post("/api/devices/:id/command", (req, res) => {
  if (!authorized(req)) return res.status(401).json({ error: "Unauthorized" });
  const device = devices.get(req.params.id);
  if (!device || device.status !== "online" || device.socket.readyState !== 1) {
    return res.status(404).json({ error: "Device offline" });
  }

  const allowed = new Set(["get_status", "get_permissions"]);
  if (!allowed.has(req.body?.command)) {
    return res.status(400).json({ error: "Command not enabled in this foundation build" });
  }

  const id = crypto.randomUUID();
  device.socket.send(JSON.stringify({ type: "command", id, command: req.body.command }));
  res.json({ ok: true, id });
});

server.listen(PORT, () => console.log(`Tost server listening on :${PORT}`));

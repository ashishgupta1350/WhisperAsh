const { BrowserWindow } = require("electron");

// WhisperAsh: lets the Cloudflare sync hear about local history/dictionary
// changes without touching every writer.
let broadcastHook = null;

function setBroadcastHook(fn) {
  broadcastHook = typeof fn === "function" ? fn : null;
}

function broadcastToWindows(channel, data) {
  const windows = BrowserWindow.getAllWindows();
  windows.forEach((win) => {
    if (!win.isDestroyed()) {
      win.webContents.send(channel, data);
    }
  });
  if (broadcastHook) {
    try {
      broadcastHook(channel, data);
    } catch {
      /* the hook must never break a broadcast */
    }
  }
}

module.exports = { broadcastToWindows, setBroadcastHook };

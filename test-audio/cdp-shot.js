// Screenshot a WhisperAsh window's page via DevTools (test helper).
//   node cdp-shot.js <port> <url-substring> <out.png>
const path = require("path");
const fs = require("fs");
const WebSocket = require(path.join(__dirname, "..", "app", "node_modules", "ws"));
(async () => {
  const [port, match, out] = process.argv.slice(2);
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const t = targets.find((x) => x.type === "page" && x.url.includes(match));
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((r) => ws.on("open", r));
  ws.on("message", (raw) => {
    const m = JSON.parse(raw);
    if (m.id !== 1) return;
    fs.writeFileSync(out, Buffer.from(m.result.data, "base64"));
    console.log("saved", out);
    ws.close();
  });
  ws.send(JSON.stringify({ id: 1, method: "Page.captureScreenshot", params: { format: "png" } }));
})();

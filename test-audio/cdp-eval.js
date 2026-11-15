// Evaluate JavaScript inside a running WhisperAsh window via the Chrome DevTools
// Protocol (test helper; WhisperAsh must be started with --remote-debugging-port).
//   node cdp-eval.js <port> <url-substring> "<expression>"
// Prints the JSON result. Uses the `ws` package from ../app/node_modules.
const path = require("path");
const WebSocket = require(path.join(__dirname, "..", "app", "node_modules", "ws"));

async function main() {
  const [port, match, expression] = process.argv.slice(2);
  const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
  const target = targets.find((t) => t.type === "page" && t.url.includes(match));
  if (!target) {
    console.error("No target matching", match, "\nTargets:", targets.map((t) => t.url).join("\n"));
    process.exit(2);
  }
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((r) => ws.on("open", r));
  ws.send(
    JSON.stringify({
      id: 1,
      method: "Runtime.evaluate",
      params: { expression, awaitPromise: true, returnByValue: true },
    })
  );
  ws.on("message", (raw) => {
    const msg = JSON.parse(raw);
    if (msg.id !== 1) return;
    const r = msg.result;
    if (r.exceptionDetails) console.log("EXCEPTION", JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails));
    else console.log(JSON.stringify(r.result.value, null, 2));
    ws.close();
  });
}
main().catch((e) => {
  console.error(e.message);
  process.exit(1);
});

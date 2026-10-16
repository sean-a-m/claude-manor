#!/usr/bin/env node
// A stand-in for `pi --mode rpc` in tests: free, offline, deterministic.
// Replies "reply N to: <message> [session S]", where N counts this session's
// messages (persisted in --session-dir, so it survives restarts).
// Special messages: "!slow" never finishes until aborted; "!error" fails.

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const arg = (flag) => { const i = process.argv.indexOf(flag); return i >= 0 ? process.argv[i + 1] : null; };
const sessionDir = arg("--session-dir");
const sessionId = arg("--session-id");
const countFile = join(sessionDir, `${sessionId}.count`);
mkdirSync(sessionDir, { recursive: true });

let count = 0;
try { count = Number(readFileSync(countFile, "utf8")); } catch {}
let lastText = null;
let slow = false;

const out = (rec) => process.stdout.write(JSON.stringify(rec) + "\n");
const assistant = (extra) => ({ type: "message_end", message: { role: "assistant", usage: { cost: { total: 0.001 } }, ...extra } });

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const cmd = JSON.parse(buf.slice(0, i));
    buf = buf.slice(i + 1);
    handle(cmd);
  }
});
process.stdin.on("end", () => process.exit(0));

function handle(cmd) {
  const ok = (data) => out({ id: cmd.id, type: "response", command: cmd.type, success: true, data });
  switch (cmd.type) {
    case "prompt":
      ok({ disposition: "started" });
      out({ type: "agent_start" });
      if (cmd.message === "!slow") { slow = true; return; }
      if (cmd.message === "!error") {
        lastText = null;
        out(assistant({ stopReason: "error", errorMessage: "boom" }));
        return out({ type: "agent_settled", aborted: false });
      }
      count += 1;
      writeFileSync(countFile, String(count));
      lastText = `reply ${count} to: ${cmd.message} [session ${sessionId}]`;
      out(assistant({ stopReason: "stop", content: [{ type: "text", text: lastText }] }));
      return out({ type: "agent_settled", aborted: false });
    case "abort":
      if (slow) { slow = false; out({ type: "agent_settled", aborted: true }); }
      return ok();
    case "get_last_assistant_text":
      return ok({ text: lastText });
    default:
      return out({ id: cmd.id, type: "response", command: cmd.type, success: false, error: "unsupported" });
  }
}

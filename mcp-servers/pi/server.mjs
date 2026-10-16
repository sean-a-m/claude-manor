#!/usr/bin/env node
// pi MCP server for manor.
//
// One tool, send_message {session, message} -> pi's reply as text. Each
// session is a long-lived `pi --mode rpc` process with its own workspace
// directory and a persisted conversation, so a session remembers earlier
// messages, also across server restarts (same state dir and session name).
//
// This server is where safety is decided: which command starts pi (e.g. a
// sandbox wrapper), which model, which tools pi may use, and whether pi
// loads extensions, skills, context files or its own MCP servers. Settings
// come from environment variables:
//
//   PI_MCP_COMMAND        command that starts pi             default "pi"
//   PI_MCP_PROVIDER       pi --provider                      default "openrouter"
//   PI_MCP_MODEL          pi --model                         default "deepseek/deepseek-v4.1-flash"
//   PI_MCP_THINKING       pi --thinking                      default "low"
//   PI_MCP_TOOLS          comma-separated pi tools; empty = no tools   default ""
//   PI_MCP_ISOLATED       "1": no extensions, skills, prompt templates,
//                         context files or MCP in pi          default "1"
//   PI_MCP_SYSTEM_PROMPT  replaces pi's system prompt          default: pi's own
//   PI_MCP_STATE_DIR      where session workspaces live       default $MANOR_RUN_DIR/pi or ./pi-state
//   PI_MCP_TIMEOUT_MS     longest pi run per message          default 240000
//   PI_MCP_MAX_SESSIONS   most sessions per server            default 16
//   PI_MCP_MAX_MESSAGE_CHARS  longest accepted message        default 8000
//
// Each session runs with its workspace (<state>/<session>/) as working
// directory; its conversation is stored in <state>/<session>/.pi-sessions/.
// MCP transport: newline-delimited JSON-RPC 2.0 on stdin/stdout. Logs go to
// stderr.

import { spawn } from "node:child_process";
import { mkdirSync } from "node:fs";
import { join, resolve } from "node:path";

const env = process.env;
const config = {
  command: env.PI_MCP_COMMAND || "pi",
  provider: env.PI_MCP_PROVIDER || "openrouter",
  model: env.PI_MCP_MODEL || "deepseek/deepseek-v4.1-flash",
  thinking: env.PI_MCP_THINKING || "low",
  tools: (env.PI_MCP_TOOLS || "").trim(),
  isolated: (env.PI_MCP_ISOLATED ?? "1") !== "0",
  systemPrompt: env.PI_MCP_SYSTEM_PROMPT || null,
  stateDir: resolve(env.PI_MCP_STATE_DIR || (env.MANOR_RUN_DIR ? join(env.MANOR_RUN_DIR, "pi") : "pi-state")),
  timeoutMs: Number(env.PI_MCP_TIMEOUT_MS || 240000),
  maxSessions: Number(env.PI_MCP_MAX_SESSIONS || 16),
  maxMessageChars: Number(env.PI_MCP_MAX_MESSAGE_CHARS || 8000),
};

const log = (...xs) => process.stderr.write(`[pi-mcp] ${xs.join(" ")}\n`);

// ---------------------------------------------------------------------------
// JSONL framing. Split only on LF (not on U+2028/U+2029 like readline does).

function onLines(stream, handle) {
  let buf = "";
  stream.setEncoding("utf8");
  stream.on("data", (chunk) => {
    buf += chunk;
    let i;
    while ((i = buf.indexOf("\n")) >= 0) {
      const line = buf.slice(0, i).replace(/\r$/, "");
      buf = buf.slice(i + 1);
      if (line.trim()) handle(line);
    }
  });
}

// ---------------------------------------------------------------------------
// pi sessions

const SESSION_RE = /^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,62}[A-Za-z0-9])?$/;
const sessions = new Map(); // name -> session

function piArgs(name, workspace) {
  const args = ["--mode", "rpc",
    "--provider", config.provider, "--model", config.model, "--thinking", config.thinking,
    "--session-dir", join(workspace, ".pi-sessions"), "--session-id", name];
  args.push(...(config.tools ? ["--tools", config.tools] : ["--no-tools"]));
  if (config.isolated) args.push("--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp");
  if (config.systemPrompt) args.push("--system-prompt", config.systemPrompt);
  return args;
}

function startSession(name) {
  const workspace = join(config.stateDir, name);
  mkdirSync(workspace, { recursive: true });
  const proc = spawn(config.command, piArgs(name, workspace), { cwd: workspace, stdio: ["pipe", "pipe", "pipe"] });
  const s = { name, proc, nextId: 0, pending: new Map(), listeners: new Set(), chain: Promise.resolve(), dead: false };

  onLines(proc.stdout, (line) => {
    let rec;
    try { rec = JSON.parse(line); } catch { return log(name, "non-JSON from pi:", line.slice(0, 200)); }
    if (rec.type === "response" && rec.id && s.pending.has(rec.id)) {
      const { resolve: ok } = s.pending.get(rec.id);
      s.pending.delete(rec.id);
      ok(rec);
    } else if (rec.type === "extension_ui_request" && ["select", "confirm", "input", "editor"].includes(rec.method)) {
      send(s, { type: "extension_ui_response", id: rec.id, cancelled: true });
    } else {
      for (const f of s.listeners) f(rec);
    }
  });
  proc.stderr.on("data", (d) => process.stderr.write(`[pi ${name}] ${d}`));
  proc.stdin.on("error", () => {});
  proc.on("error", (e) => die(s, `could not start pi: ${e.message}`));
  proc.on("exit", (code) => die(s, `pi exited (code ${code})`));
  sessions.set(name, s);
  log("started session", name, "in", workspace);
  return s;
}

function die(s, reason) {
  if (s.dead) return;
  s.dead = true;
  sessions.delete(s.name);
  for (const { reject } of s.pending.values()) reject(new Error(reason));
  s.pending.clear();
  for (const f of s.listeners) f({ type: "process_exit", reason });
  log(s.name, reason);
}

function send(s, record) {
  if (!s.dead) s.proc.stdin.write(JSON.stringify(record) + "\n");
}

function command(s, record) {
  return new Promise((ok, reject) => {
    if (s.dead) return reject(new Error("pi is not running"));
    const id = `c${++s.nextId}`;
    s.pending.set(id, { resolve: ok, reject });
    send(s, { ...record, id });
  });
}

function getSession(name) {
  const s = sessions.get(name);
  if (s) return s;
  if (sessions.size >= config.maxSessions) throw new Error(`session limit (${config.maxSessions}) reached`);
  return startSession(name);
}

// Send one prompt and wait until pi settles. Resolves {text, cost} or
// rejects with a readable error. Calls on one session run one at a time.
function prompt(s, message, signal) {
  const run = () => new Promise((ok, reject) => {
    let cost = 0, lastError = null, timer = null;
    const finish = (f) => {
      clearTimeout(timer);
      s.listeners.delete(listener);
      signal.removeEventListener("abort", onAbort);
      f();
    };
    const listener = (rec) => {
      if (rec.type === "message_end" && rec.message?.role === "assistant") {
        cost += rec.message.usage?.cost?.total || 0;
        if (rec.message.stopReason === "error") lastError = rec.message.errorMessage || "model error";
      } else if (rec.type === "agent_settled") {
        if (rec.aborted) return finish(() => reject(new Error(lastError || "aborted")));
        command(s, { type: "get_last_assistant_text" })
          .then((r) => {
            const text = r.data?.text;
            finish(() => text ? ok({ text, cost }) : reject(new Error(lastError || "pi gave no reply")));
          })
          .catch((e) => finish(() => reject(e)));
      } else if (rec.type === "process_exit") {
        finish(() => reject(new Error(rec.reason)));
      }
    };
    const abort = (why) => { lastError = why; send(s, { type: "abort" }); };
    const onAbort = () => abort("cancelled by the client");
    s.listeners.add(listener);
    signal.addEventListener("abort", onAbort);
    timer = setTimeout(() => abort(`timed out after ${config.timeoutMs} ms`), config.timeoutMs);
    command(s, { type: "prompt", message })
      .then((r) => {
        if (!r.success) finish(() => reject(new Error(r.error || "prompt rejected")));
        else if (r.data?.disposition === "handled") finish(() => reject(new Error("pi handled the message as a command")));
      })
      .catch((e) => finish(() => reject(e)));
  });
  const result = s.chain.then(run, run);
  s.chain = result.catch(() => {});
  return result;
}

// ---------------------------------------------------------------------------
// MCP

const TOOL = {
  name: "send_message",
  description: "Send a message to a persistent pi assistant session and return its reply. Each session keeps its own conversation.",
  inputSchema: {
    type: "object",
    properties: {
      session: { type: "string", description: "Session name: letters, digits, '.', '_' or '-'" },
      message: { type: "string", description: "The message to send" },
    },
    required: ["session", "message"],
  },
};

const inflight = new Map(); // JSON-RPC request id -> AbortController

function reply(id, result) { process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, result }) + "\n"); }
function fail(id, code, message) { process.stdout.write(JSON.stringify({ jsonrpc: "2.0", id, error: { code, message } }) + "\n"); }
const toolError = (text) => ({ content: [{ type: "text", text }], isError: true });

async function callTool(id, params) {
  if (params?.name !== TOOL.name) return toolError(`unknown tool: ${params?.name}`);
  const { session, message } = params.arguments || {};
  if (typeof session !== "string" || !SESSION_RE.test(session)) return toolError("invalid session name");
  if (typeof message !== "string" || !message.trim()) return toolError("message must be a non-empty string");
  if (message.length > config.maxMessageChars) return toolError(`message is longer than ${config.maxMessageChars} characters`);
  const ctl = new AbortController();
  inflight.set(id, ctl);
  try {
    const { text, cost } = await prompt(getSession(session), message, ctl.signal);
    return { content: [{ type: "text", text }], _meta: { "manor/cost": cost } };
  } catch (e) {
    return toolError(e.message);
  } finally {
    inflight.delete(id);
  }
}

async function handle(msg) {
  const { id, method, params } = msg;
  if (id === undefined) {
    if (method === "notifications/cancelled") inflight.get(params?.requestId)?.abort();
    return;
  }
  if (!method) return; // a response to something we never sent
  switch (method) {
    case "initialize":
      return reply(id, {
        protocolVersion: params?.protocolVersion || "2025-06-18",
        capabilities: { tools: {} },
        serverInfo: { name: "manor-pi", version: "0.1.0" },
      });
    case "ping": return reply(id, {});
    case "tools/list": return reply(id, { tools: [TOOL] });
    case "tools/call": return reply(id, await callTool(id, params));
    default: return fail(id, -32601, `method not found: ${method}`);
  }
}

onLines(process.stdin, (line) => {
  let msg;
  try { msg = JSON.parse(line); } catch { return fail(null, -32700, "parse error"); }
  handle(msg).catch((e) => msg.id !== undefined && fail(msg.id, -32603, e.message));
});

// Closing stdin is the client's shutdown signal: let each pi exit cleanly.
process.stdin.on("end", () => {
  for (const s of sessions.values()) s.proc.stdin.end();
  setTimeout(() => process.exit(0), 3000).unref();
});

log("ready; command", config.command, "model", `${config.provider}/${config.model}`,
    "tools", config.tools || "(none)", "state", config.stateDir);

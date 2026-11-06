#!/usr/bin/env node
// pi-llm: manor's model calls through pi's model layer, as an MCP server.
//
// One tool, complete {model, messages, tools, max-tokens, reasoning,
// tool-choice} -> one assistant reply in manor's response shape (see
// manor.io.llm). Requests and replies use OpenAI chat-completions messages,
// as manor builds them; this server converts them to and from pi-ai's and
// calls ModelRuntime.completeSimple, so every provider pi knows works, with
// pi's own credentials (~/.pi/agent/auth.json, models.json, OAuth logins)
// and pi's prompt caching. Nothing is kept between calls except the recent
// replies below.
//
// Models are "<pi provider>/<model id>", e.g. "anthropic/claude-haiku-5-5"
// or "openai-codex/gpt-6-luna". A name that isn't one is tried as an
// OpenRouter id, so manor's usual ids ("deepseek/deepseek-v4.1-flash") work
// unchanged; "openrouter/<id>" forces OpenRouter.
//
// Thinking: manor never sends a reply's thinking back. Some providers need
// it within a turn (Anthropic's thinking with tool use), so this server keeps
// each recent reply by its tool-call ids and, when manor sends that assistant
// message back, sends pi's original instead. pi-ai passes thinking only to
// the model that wrote it.
//
// Providers that pi extensions add aren't loaded. pi-claude-bridge's
// "claude-bridge" couldn't serve these calls anyway: it works only inside
// pi's agent loop and always runs Claude Code's own system prompt.
//
// Environment:
//   PI_LLM_MODULE             pi-coding-agent's module   default: found from `pi` on PATH
//   MANOR_OPENROUTER_API_KEY  used for OpenRouter models in place of pi's key (in memory only)
// MCP transport: newline-delimited JSON-RPC 2.0 on stdin/stdout. Logs go to stderr.

import { existsSync, readFileSync, realpathSync } from "node:fs";
import { dirname, join } from "node:path";
import { pathToFileURL } from "node:url";

const env = process.env;
const log = (...xs) => process.stderr.write(`[pi-llm] ${xs.join(" ")}\n`);

// ---------------------------------------------------------------------------
// pi's model runtime

function piModule() {
  if (env.PI_LLM_MODULE) return env.PI_LLM_MODULE;
  const bin = (env.PATH || "").split(":").map((d) => join(d, "pi")).find(existsSync);
  if (!bin) throw new Error("pi is not on PATH; set PI_LLM_MODULE");
  for (let dir = dirname(realpathSync(bin)); dir !== dirname(dir); dir = dirname(dir)) {
    const pkg = join(dir, "package.json");
    if (existsSync(pkg) && JSON.parse(readFileSync(pkg, "utf8")).name === "@earendil-works/pi-coding-agent") {
      return join(dir, "dist", "index.js");
    }
  }
  throw new Error(`no pi-coding-agent package above ${bin}; set PI_LLM_MODULE`);
}

const { ModelRuntime } = await import(pathToFileURL(piModule()).href);
const runtime = await ModelRuntime.create();
if (env.MANOR_OPENROUTER_API_KEY) await runtime.setRuntimeApiKey("openrouter", env.MANOR_OPENROUTER_API_KEY);

function resolveModel(name) {
  const i = name.indexOf("/");
  const model = (i > 0 && runtime.getModel(name.slice(0, i), name.slice(i + 1))) || runtime.getModel("openrouter", name);
  if (!model) throw new Error(`pi knows no model ${name}: use <pi provider>/<model id>, or an OpenRouter id`);
  return model;
}

// ---------------------------------------------------------------------------
// Recent replies, by tool-call id, so their thinking can go back as it came

const recent = new Map();
const RECENT_MAX = 2000;

function remember(reply) {
  for (const b of reply.content) if (b.type === "toolCall") recent.set(b.id, reply);
  while (recent.size > RECENT_MAX) recent.delete(recent.keys().next().value);
}

// ---------------------------------------------------------------------------
// OpenAI chat messages -> pi-ai context

const text = (content) =>
  typeof content === "string" ? content
    : Array.isArray(content) ? content.filter((p) => p.type === "text").map((p) => p.text).join("\n")
    : "";

function parseArguments(s) {
  try { return JSON.parse(s || "{}"); } catch { return {}; }
}

const ZERO_USAGE = { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0,
                     cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } };

function assistantMessage(m, model, timestamp) {
  const calls = m.tool_calls || [];
  const kept = calls.length > 0 && recent.get(calls[0].id);
  if (kept) return kept;
  return {
    role: "assistant",
    content: [
      ...(text(m.content) ? [{ type: "text", text: text(m.content) }] : []),
      ...calls.map((c) => ({ type: "toolCall", id: c.id, name: c.function.name, arguments: parseArguments(c.function.arguments) })),
    ],
    api: model.api, provider: model.provider, model: model.id,
    usage: ZERO_USAGE, stopReason: calls.length ? "toolUse" : "stop", timestamp,
  };
}

function toContext(messages, tools, model) {
  const timestamp = Date.now();
  const toolNames = new Map();
  let systemPrompt;
  const out = [];
  for (const m of messages) {
    if (m.role === "system" && out.length === 0 && systemPrompt === undefined) systemPrompt = text(m.content);
    else if (m.role === "system") out.push({ role: "system", content: text(m.content), timestamp });
    else if (m.role === "user") out.push({ role: "user", content: text(m.content), timestamp });
    else if (m.role === "assistant") {
      for (const c of m.tool_calls || []) toolNames.set(c.id, c.function.name);
      out.push(assistantMessage(m, model, timestamp));
    } else if (m.role === "tool") {
      out.push({ role: "toolResult", toolCallId: m.tool_call_id, toolName: toolNames.get(m.tool_call_id) || "",
                 content: [{ type: "text", text: text(m.content) }], isError: false, timestamp });
    }
  }
  return {
    systemPrompt,
    messages: out,
    tools: (tools || []).map((t) => ({ name: t.function.name, description: t.function.description || "",
                                       parameters: t.function.parameters || { type: "object", properties: {} } })),
  };
}

// ---------------------------------------------------------------------------
// pi-ai reply -> manor's response (keys as manor.io.llm's, read as keywords)

const FINISH = { toolUse: "tool_calls", stop: "stop", length: "length" };

function toResponse(reply) {
  const blocks = reply.content;
  const content = blocks.filter((b) => b.type === "text").map((b) => b.text).join("") || null;
  const thoughts = blocks.filter((b) => b.type === "thinking").map((b) => b.thinking).join("\n") || null;
  const calls = blocks.filter((b) => b.type === "toolCall")
    .map((b) => ({ id: b.id, name: b.name, arguments: JSON.stringify(b.arguments ?? {}) }));
  const u = reply.usage;
  return {
    message: {
      role: "assistant", content,
      ...(calls.length ? { tool_calls: calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: c.arguments } })) } : {}),
    },
    content,
    thoughts,
    "finish-reason": FINISH[reply.stopReason] || reply.stopReason,
    "tool-calls": calls,
    usage: {
      "prompt-tokens": u.input + u.cacheRead + u.cacheWrite,
      "completion-tokens": u.output,
      "thought-tokens": u.reasoning ?? null,
      cost: u.cost.total,
      "cached-tokens": u.cacheRead,
      "cache-write-tokens": u.cacheWrite,
    },
    provider: `pi:${reply.provider}`,
    ...(reply.responseId ? { "generation-id": reply.responseId } : {}),
  };
}

async function complete(args) {
  const model = resolveModel(args.model);
  const effort = args.reasoning?.effort;
  const reply = await runtime.completeSimple(model, toContext(args.messages, args.tools, model), {
    maxTokens: args["max-tokens"] || undefined,
    reasoning: effort && effort !== "none" ? effort : undefined,
    toolChoice: args.tools?.length ? args["tool-choice"] || "auto" : undefined,
  });
  if (reply.stopReason === "error" || reply.stopReason === "aborted") {
    throw new Error(`${model.provider}/${model.id}: ${reply.errorMessage || reply.stopReason}`);
  }
  remember(reply);
  return toResponse(reply);
}

// ---------------------------------------------------------------------------
// MCP

const TOOL = {
  name: "complete",
  description: "One model call: OpenAI chat messages and tools in, one assistant reply out.",
  inputSchema: { type: "object", properties: { model: { type: "string" }, messages: { type: "array" } }, required: ["model", "messages"] },
};

const write = (msg) => process.stdout.write(JSON.stringify({ jsonrpc: "2.0", ...msg }) + "\n");

async function handle({ id, method, params }) {
  if (id === undefined || !method) return;
  switch (method) {
    case "initialize":
      return write({ id, result: { protocolVersion: params?.protocolVersion || "2025-06-18",
                                   capabilities: { tools: {} }, serverInfo: { name: "manor-pi-llm", version: "0.1.0" } } });
    case "ping": return write({ id, result: {} });
    case "tools/list": return write({ id, result: { tools: [TOOL] } });
    case "tools/call":
      if (params?.name !== TOOL.name) return write({ id, result: { content: [{ type: "text", text: `unknown tool: ${params?.name}` }], isError: true } });
      try {
        const response = await complete(params.arguments || {});
        return write({ id, result: { content: [{ type: "text", text: "ok" }], structuredContent: response } });
      } catch (e) {
        return write({ id, result: { content: [{ type: "text", text: e.message }], isError: true } });
      }
    default: return write({ id, error: { code: -32601, message: `method not found: ${method}` } });
  }
}

let buf = "";
process.stdin.setEncoding("utf8");
process.stdin.on("data", (chunk) => {
  buf += chunk;
  let i;
  while ((i = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, i).trim();
    buf = buf.slice(i + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { write({ id: null, error: { code: -32700, message: "parse error" } }); continue; }
    handle(msg).catch((e) => msg.id !== undefined && write({ id: msg.id, error: { code: -32603, message: e.message } }));
  }
});
process.stdin.on("end", () => process.exit(0));

log("ready; openrouter key", env.MANOR_OPENROUTER_API_KEY ? "manor's" : "pi's");

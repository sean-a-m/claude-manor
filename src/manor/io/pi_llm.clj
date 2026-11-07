(ns manor.io.pi-llm
  "Model calls through pi's model layer instead of straight to OpenRouter
  (run option :llm :pi, CLI --pi). The calls go to a long-lived helper,
  mcp-servers/pi-llm/server.mjs, an MCP server started on the first call and
  closed with the run; it converts manor's requests for pi-ai and calls the
  provider with pi's own credentials and prompt caching. Requests and
  responses are those of manor.io.llm, so nothing else changes.

  Models are \"<pi provider>/<model id>\" (\"openai-codex/gpt-6-luna\");
  other names are OpenRouter ids, as with manor.io.llm/openrouter. A
  request's :cache-control is not sent: pi caches by itself, and its
  :cache-key goes as pi-ai's sessionId (OpenAI's prompt_cache_key, session
  affinity on OpenRouter and Anthropic). The helper's
  stderr goes to <run-dir>/mcp-pi-llm.log.

  Machine settings (e.g. :env {\"PI_LLM_MODULE\" ...}) go under :pi-llm in
  config/mcp.edn, as for MCP servers. Providers from pi extensions are
  available when the helper's PI_LLM_EXTENSIONS (a colon-separated list of
  extension paths) names them: pi-claude-code's claude-code provider then
  serves claude-code/<model id>, using Claude Code's claude -p on a Claude
  subscription. Not pi-claude-bridge: it is not a provider (see the helper's
  header)."
  (:require [manor.io.llm :as llm]
            [manor.io.mcp :as mcp])
  (:import (java.io Closeable)))

(def server
  "How to start the helper (a manor.io.mcp server config)."
  {:command ["node" "mcp-servers/pi-llm/server.mjs"]})

(def ^:private timeout-ms 300000)

(defrecord PiLlm [pool]
  llm/LLM
  (complete [_ request]
    (:value (mcp/call pool :pi-llm "complete"
                      (select-keys request [:model :messages :tools :max-tokens :reasoning :tool-choice :cache-key])
                      timeout-ms)))
  Closeable
  (close [_] (mcp/close-all! pool)))

(defn pi-llm
  "A pi-backed LLM for the run in `ctx` ({:run-id :run-dir}): `server` with
  config/mcp.edn's :pi-llm over it, or `config` (tests start a fake pi so)."
  ([ctx] (pi-llm ctx (:pi-llm (mcp/server-configs {:pi-llm server} (select-keys (mcp/local-config) [:pi-llm])))))
  ([ctx config] (->PiLlm (mcp/pool {:pi-llm config} ctx))))

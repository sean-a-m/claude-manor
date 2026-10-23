#!/usr/bin/env bash
# Free/offline actual-browser check. Run from the repository root.
set -euo pipefail
PORT=${1:-8097}
[[ "$PORT" =~ ^809[0-9]$ ]] || { echo 'Use a port in 8090–8099'; exit 1; }
if ss -ltn | grep -q ":$PORT "; then echo "Port $PORT is occupied"; exit 1; fi
TMP=$(mktemp -d /tmp/manor-scene-check.XXXXXX)
export SCENE_STOP="$TMP/stop" SCENE_PORT="$PORT" SCENE_SCENARIO="${SCENE_SCENARIO:-village}"
clojure -M:dev -e '
(require (quote [manor.engine :as e]) (quote [manor.tools.web :as web]))
(let [run (e/start-run (keyword (System/getenv "SCENE_SCENARIO")) {:llm :mock :offline? true :test? true
                                :run-id (str "scene-browser-" (random-uuid))})]
  (try
    (web/start! run (parse-long (System/getenv "SCENE_PORT")))
    (loop [n 0]
      (when (and (< n 1800) (not (.exists (java.io.File. (System/getenv "SCENE_STOP")))))
        (Thread/sleep 100) (recur (inc n))))
    (finally (web/stop!) (e/stop-run run))))
(shutdown-agents)' >"$TMP/server.log" 2>&1 &
SERVER=$!
cleanup() { touch "$SCENE_STOP"; wait "$SERVER" || cat "$TMP/server.log"; rm -rf "$TMP"; }
trap cleanup EXIT
READY=0
for _ in $(seq 1 150); do
  if curl -fsS "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then READY=1; break; fi
  if ! kill -0 "$SERVER" 2>/dev/null; then cat "$TMP/server.log"; exit 1; fi
  sleep .2
done
[[ "$READY" == 1 ]] || { cat "$TMP/server.log"; exit 1; }
node test/manor/scene_client_test.mjs "http://127.0.0.1:$PORT"

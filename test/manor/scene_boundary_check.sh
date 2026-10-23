#!/usr/bin/env bash
# Compare unchanged probe/options in an archived base and this checkout; no ports/models.
set -euo pipefail
BASE=${1:-0f7eae9}
ROOT=$(git rev-parse --show-toplevel)
cd "$ROOT"
# Fail closed if a protected runtime seam or any existing scenario definition changed.
git diff --exit-code "$BASE" -- src/manor/engine.clj src/manor/engine src/manor/mechanics \
  src/manor/world.clj src/manor/prompt.clj src/manor/actions.clj src/manor/io \
  src/manor/clock.clj src/manor/rng.clj src/manor/narrate.clj src/manor/main.clj \
  deps.edn resources/worlds resources/positions resources/architect mcp-servers
while IFS= read -r path; do
  case "$path" in
    src/manor/tools/scene*.clj|src/manor/tools/web.clj|src/manor/scene_layout.clj|src/manor/scenarios/claudeworth.clj) ;;
    *) echo "Unexpected source change: $path"; exit 1 ;;
  esac
done < <(git diff --name-only "$BASE" -- src/)
OUT=$(mktemp -d /tmp/manor-scene-boundary.XXXXXX)
mkdir "$OUT/base"
git archive "$BASE" | tar -x -C "$OUT/base"
# The only permitted scenario additions are the pure require and optional hook.
sed '/^            \[manor.tools.scene-claudeworth :as scene\]$/d; /^     :scene scene\/hooks$/d' \
  src/manor/scenarios/claudeworth.clj > "$OUT/scenario-without-hook.clj"
cmp "$OUT/base/src/manor/scenarios/claudeworth.clj" "$OUT/scenario-without-hook.clj"
export SCENE_BOUNDARY_OUT="$OUT"
PROBE="$ROOT/test/manor/scene_boundary_probe.clj"
(cd "$OUT/base"; clojure -M:dev -i "$PROBE" -e \
  '(manor.scene-boundary-probe/-main (str (System/getenv "SCENE_BOUNDARY_OUT") "/base.edn") "base")') >"$OUT/base.log" 2>&1
clojure -M:dev -m manor.scene-boundary-probe "$OUT/head.edn" render >"$OUT/head.log" 2>&1
clojure -M:dev -m manor.scene-boundary-probe "$OUT/no-hooks.edn" no-hooks >"$OUT/no-hooks.log" 2>&1
clojure -M:dev -e '
(require (quote [clojure.edn :as edn]) (quote [clojure.java.io :as io])
         (quote [manor.scene-boundary-probe :as probe]))
(let [dir (System/getenv "SCENE_BOUNDARY_OUT")
      read! #(edn/read-string (slurp (io/file dir %)))
      base (read! "base.edn") head (read! "head.edn") plain (read! "no-hooks.edn")]
  (doseq [[scenario b] (sort-by key base)]
    (doseq [[label data] [["rendered head" head] ["head without hooks" plain]]]
      (when-not (= (probe/comparable b) (probe/comparable (get data scenario)))
        (throw (ex-info "Simulation boundary comparison failed" {:scenario scenario :mode label}))))
    (println scenario "equal:" (count (:prefixes b)) "prefixes,"
             (count (:events (last (:prefixes b)))) "events," (count (:requests b)) "complete model requests"))
  (when-not (= (set (keys base)) (set (keys head)) (set (keys plain)))
    (throw (ex-info "Comparison cases differ" {})))
  (println "Only excluded field: top-level :at in durable call envelopes. No world/event/request payload exclusions.")
  (println "Contiguous work judges on an identical world compare in job order; raw dispatch order is retained in EDN.")
  (println "Tool schema regexes retain source and flags as EDN data."))
(shutdown-agents)' | tee "$OUT/comparison.txt"
echo "Protected runtime source unchanged; scenario differs only by optional presentation wiring."
echo "Evidence retained at $OUT (archive, EDN, logs, comparison.txt)."

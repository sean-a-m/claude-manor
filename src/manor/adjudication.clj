(ns manor.adjudication
  "Pure response handling for narrow engine-run LLM judgements. Domain code
  supplies the tool and argument checker; the engine owns calls and events.")

(defn answer
  "Check the first call of `tool`, replying to every tool call (or nudging
  if none). `check` receives raw arguments and returns a verdict or {:error}.
  This does not apply effects."
  [tool response check]
  (let [calls (:tool-calls response)
        index (first (keep-indexed #(when (= tool (:name %2)) %1) calls))
        {:keys [error] :as verdict} (if (some? index)
                                     (check (:arguments (nth calls index)))
                                     {:error (str "no " tool " call")})
        replies (if (seq calls)
                  (mapv (fn [i call]
                          {:role "tool" :tool_call_id (:id call)
                           :content (cond
                                      (not= tool (:name call)) (str "error: there is no tool called " (:name call) "; call " tool)
                                      (not= i index) (str "ignored: only your first " tool " call counts")
                                      error (str "error: " error ". Call " tool " again with a corrected answer.")
                                      :else "ok")})
                        (range) calls)
                  [{:role "user" :content (str "You must answer by calling the " tool " tool.")}])]
    (cond-> {:replies replies}
      error (assoc :error error)
      (not error) (assoc :verdict verdict))))

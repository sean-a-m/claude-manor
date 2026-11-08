(ns manor.money-test
  (:require [clojure.test :refer [deftest is]]
            [manor.actions :as actions]
            [manor.io.worldfile :as worldfile]
            [manor.narrate :as narrate]
            [manor.mechanics.building :as building]
            [manor.mechanics.money :as money]
            [manor.mechanics.shop :as shop]
            [manor.settings :as settings]))

(def england (money/spec {}))
(def tael {:units [{:value 1000 :singular "tael" :plural "taels"}
                   {:value 1 :singular "cash" :plural "cash"}]
           :zero "0 cash" :base-name "cash" :authored 1000 :authored-name "whole taels"})

(deftest old-sterling
  (is (= 720000 (money/authored->units england 3000)))
  (is (= 0 (money/authored->units england 0)))
  (is (= -480 (money/authored->units england -2)))
  (doseq [[n label] [[0 "0d"] [1 "1d"] [11 "11d"] [12 "1s"] [18 "1s 6d"]
                    [239 "19s 11d"] [240 "£1"] [241 "£1 1d"] [258 "£1 1s 6d"]
                    [720000 "£3000"] [-18 "-1s 6d"] [-258 "-£1 1s 6d"]]]
    (is (= label (money/display england n)))))

(deftest another-currency
  (is (= tael (money/spec {:state {:setting-pack {:money tael}}})))
  (doseq [[n units] [[0 0] [1 1000] [-2 -2000]]]
    (is (= units (money/authored->units tael n))))
  (doseq [[n label] [[0 "0 cash"] [1 "1 cash"] [250 "250 cash"] [1000 "1 tael"]
                    [3250 "3 taels 250 cash"] [-1001 "-1 tael 1 cash"]]]
    (is (= label (money/display tael n)))))

(deftest the-spec-reaches-authoring-pricing-and-narration
  (let [pack (-> (settings/pack {}) (assoc :money tael)
                 (assoc-in [:prompts "money-pricing.md"] "Price in integer {{money.base-name}}."))
        world {:state {:setting-pack pack}}
        wf {:meta {:starting-funds 3}
            :locations [{:id "hall" :name "Hall"}]
            :agents [{:name "Buyer" :role :buyer :purse 2} {:name "Architect" :role :architect}]}]
    (with-redefs [settings/for-worldfile (constantly pack)]
      (is (= {"household" 3000 "buyer" 2000 "architect" 0}
             (:accounts (building/initial-state wf (:agents wf) {:architect {:capabilities #{:architect}}} {}))))
      (is (= ["agent \"Buyer\": :purse must be non-negative integer whole taels"]
             (worldfile/problems (assoc-in wf [:agents 0 :purse] -1))))
      (is (= [":meta :starting-funds must be non-negative integer whole taels"]
             (vec (building/problems (assoc-in wf [:meta :starting-funds] -1))))))
    (is (= (str "Ask for the price list at an open architect's office. All prices in this result are whole taels. "
                "The result is private and remembered next turn.")
           (actions/description (first (filter #(= :price-list (:name %)) building/actions)) world)))
    (is (.contains (shop/instructions world) "Price in integer cash."))
    (is (= "buyer buys tea (1 tin) for 1 tael 1 cash at the counter, and takes the goods away."
           (narrate/describe {:type :shop/bought :actor "buyer"
                              :data {:item "tea" :qty 1 :unit "1 tin" :price 1001 :location "counter"}}
                             (narrate/names world))))))

(deftest money-text-is-parsed
  (doseq [[text n] [["£15 10s" 3720] ["15s 6d" 186] ["£3" 720] ["7d" 7] ["£1 1s 6d" 258]
                    [" £2, 6d " 486] ["£ 2 10 s" 600] ["10S" 120] ["0d" 0]]]
    (is (= n (money/parse england text)) text))
  (doseq [[text n] [["3 taels 250 cash" 3250] ["1 tael" 1000] ["250 cash" 250] ["2 Taels" 2000]]]
    (is (= n (money/parse tael text)) text))
  (doseq [bad [nil 600 "600" "" "two pounds" "15s £1" "£1 £2" "£15 10" "5 shillings" "3 taels 250"]]
    (is (nil? (money/parse england bad)) (pr-str bad)))
  (is (nil? (money/parse tael "3000")))
  (is (= "£3 10s" (money/example england)))
  (is (= "3 taels 10 cash" (money/example tael)))
  (is (re-find #"bare number.*\"£3 10s\"" (money/parse-reason england "600")))
  (is (re-find #"isn't an amount.*\"3 taels 10 cash\"" (money/parse-reason tael "three taels")))
  (is (nil? (money/parse-reason england "£2"))))

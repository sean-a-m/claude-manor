(ns manor.rng
  "Deterministic randomness without threading RNG state through the world:
  each roll is derived from the run seed plus a key such as
  [round agent-id :harvest]. Outcomes are recorded in events, so replays
  never re-roll.")

(defn roll
  "An int in [0, n) determined by the world's seed and `k`."
  [world k n]
  (if (pos? n)
    (.nextInt (java.util.SplittableRandom. (long (hash [(:seed world) k]))) (int n))
    0))

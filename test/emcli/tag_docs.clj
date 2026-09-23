(ns emcli.tag-docs
  "The wireframe tag tables in the docs are generated from emcli.wireframe's
  tag-schema, the one place the vocabulary is defined, so they cannot drift from
  what the code accepts.

  Each doc marks its generated section with `begin-marker`/`end-marker`; the
  prose around it stays hand-written. `gen` rewrites the sections (`bb
  gen-docs`); `check` reports every doc whose section is stale or whose markers
  are missing and exits non-zero (part of `bb check`).

  It lives in test/ like emcli.change-stream-check: a doc tool, not runtime
  behaviour."
  (:require [clojure.string :as str]
            [emcli.wireframe :as wf]))

(def doc-paths
  ["doc/wireframe-dsl.md"
   "plugins/event-modeling/skills/emcli-wireframing/references/wireframing.md"])

(def begin-marker "<!-- BEGIN GENERATED: wireframe tag tables (bb gen-docs) -->")
(def end-marker   "<!-- END GENERATED -->")

(defn splice
  "`text` with the section between the markers replaced by `generated`, or nil
  when the markers are missing."
  [text generated]
  (let [b (str/index-of text begin-marker)
        e (some->> b (str/index-of text end-marker))]
    (when (and b e)
      (str (subs text 0 (+ b (count begin-marker)))
           "\n" (str/trim generated) "\n"
           (subs text e)))))

(defn violations
  "One message per doc whose generated section is missing or out of date."
  []
  (let [generated (wf/tag-reference-markdown)]
    (for [path doc-paths
          :let [text    (slurp path)
                spliced (splice text generated)]
          :when (not= text spliced)]
      (if spliced
        (str path ": the tag tables are out of date - run `bb gen-docs`")
        (str path ": the generated-section markers are missing")))))

(defn gen
  "Rewrite every doc's generated section - all of them or, when any doc lacks
  its markers, none."
  [& _]
  (let [generated (wf/tag-reference-markdown)
        spliced   (into {} (map (fn [p] [p (splice (slurp p) generated)])) doc-paths)]
    (if-let [missing (seq (keep (fn [[p s]] (when-not s p)) spliced))]
      (do (run! #(println (str % ": the generated-section markers are missing")) missing)
          (System/exit 1))
      (doseq [[path text] spliced]
        (spit path text)
        (println (str "wrote " path))))))

(defn check [& _]
  (if-let [vs (seq (violations))]
    (do (run! println vs)
        (println (str "tag-docs drift check FAILED: " (count vs) " doc(s)"))
        (System/exit 1))
    (println (str "tag-docs drift check OK: " (count doc-paths) " docs match tag-schema"))))

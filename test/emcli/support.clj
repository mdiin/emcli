(ns emcli.support
  "Shared test fixtures and a thin driver for applying authoring rules in tests."
  (:require [clojure.test :refer [is]]
            [emcli.model :as m]
            [emcli.rules :as r]))

(defn ok
  "Apply a rule, assert it succeeded, and return its result map (with :store)."
  [store rule-fn args]
  (let [res (rule-fn store args)]
    (is (not (r/error? res)) (str "expected success, got " (pr-str res)))
    res))

(defn err
  "Apply a rule and return the (asserted) error result."
  [store rule-fn args]
  (let [res (rule-fn store args)]
    (is (r/error? res) (str "expected error, got " (pr-str res)))
    res))

(defn with-model
  "A fresh store containing one EventModel. Returns [store model-id]."
  ([] (with-model "Orders"))
  ([name]
   (let [{:keys [store result]} (r/create-model (m/empty-store) {:name name})]
     [store (:id result)])))

(ns emcli.persistence-test
  "EDN persistence: the store is flushed to disk on every committed write, and a
  fresh app loaded from that file reproduces the model (crash recovery)."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]))

(defn- tmp-file []
  (str (fs/path (fs/temp-dir) (str "emcli-" (System/nanoTime) ".edn"))))

(defn- author! [a]
  (let [tl (:result (cmd/run a "create-timeline" {:title "Ordering"}))
        sl (:result (cmd/run a "add-slice" {:timeline (:id tl) :title "Place" :kind "state_change" :index 0}))
        c  (:result (cmd/run a "create-element" {:name "PlaceOrder" :kind "command"}))]
    (cmd/run a "place-element" {:slice (:id sl) :element (:id c)})
    {:tl (:id tl) :sl (:id sl) :c (:id c)}))

(deftest flushes-on-every-write
  (testing "the EDN file exists after creation and is rewritten on each mutation"
    (let [file (tmp-file)
          a    (app/new-app "Orders" file)]
      (try
        (is (fs/exists? file) "initial state flushed on open")
        (cmd/run a "create-timeline" {:title "Ordering"})
        (let [after-1 (edn/read-string (slurp file))]
          (is (= 1 (count (get-in after-1 [:store :timelines]))))
          (cmd/run a "create-timeline" {:title "Viewing"})
          (let [after-2 (edn/read-string (slurp file))]
            (is (= 2 (count (get-in after-2 [:store :timelines]))) "second write flushed too")))
        (finally (fs/delete-if-exists file))))))

(deftest crash-recovery-reloads-state
  (testing "a fresh app loaded from the file reproduces the persisted model"
    (let [file (tmp-file)
          a    (app/new-app "Orders" file)
          ids  (author! a)
          ;; simulate a crash + restart: discard `a`, load from disk only.
          b    (app/load-app file)]
      (try
        (is (= "Orders" (:name (m/fetch (app/store b) :event-model (app/model-id b)))))
        (is (= 1 (count (m/timelines (app/store b) (app/model-id b)))))
        (let [tl (first (m/timelines (app/store b) (app/model-id b)))
              sl (first (m/slices (app/store b) (:id tl)))]
          (is (= "Place" (:title sl)))
          (is (= 1 (count (m/placements (app/store b) (:id sl))))))
        (testing "ids keep advancing — :seq is preserved, never reused"
          (let [next (:result (cmd/run b "create-timeline" {:title "New"}))]
            (is (> (:id next) (:tl ids)))))
        (finally (fs/delete-if-exists file))))))

(deftest open-app-loads-or-creates
  (let [file (tmp-file)]
    (try
      (testing "open-app on a missing file creates a fresh persisted model"
        (let [a (app/open-app "Fresh" file)]
          (is (fs/exists? file))
          (is (= "Fresh" (:name (m/fetch (app/store a) :event-model (app/model-id a)))))
          (cmd/run a "create-swimlane" {:name "Lane" :index 0})))
      (testing "open-app on an existing file loads it (ignores the name arg)"
        (let [b (app/open-app "Ignored" file)]
          (is (= "Fresh" (:name (m/fetch (app/store b) :event-model (app/model-id b)))))
          (is (= 1 (count (m/swimlanes (app/store b) (app/model-id b)))))))
      (finally (fs/delete-if-exists file)))))

(deftest subscriptions-are-not-persisted
  (testing "runtime subscriptions are cleared on save and absent after reload"
    (let [file (tmp-file)
          a    (app/new-app "Orders" file)]
      (try
        (app/subscribe! a (fn [_] nil))
        (is (= 1 (app/subscriber-count a)))
        ;; force a flush via a mutation, then inspect the file
        (cmd/run a "create-timeline" {:title "T"})
        (let [persisted (edn/read-string (slurp file))]
          (is (empty? (get-in persisted [:store :subscriptions]))
              "subscriptions are not written to disk"))
        (let [b (app/load-app file)]
          (is (zero? (app/subscriber-count b)))
          (is (empty? (m/all (app/store b) :subscription))))
        (finally (fs/delete-if-exists file))))))

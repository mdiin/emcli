(ns emcli.app-test
  "ChangeStream contract obligations exercised at the app level (no sockets):
  SnapshotThenDeltas, DeltaPerMutation, CanonicalShape, and the Subscribe/
  Unsubscribe lifecycle of ModelChangeStream."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]
            [emcli.rules :as r]))

(defn- recording-sub
  "Subscribe with a recorder; returns [sub-id messages-atom]."
  [a]
  (let [msgs (atom [])
        sid  (app/subscribe! a #(swap! msgs conj %))]
    [sid msgs]))

(deftest snapshot-is-first-message
  (testing "SnapshotThenDeltas: the first message a client receives is one snapshot"
    (let [a (app/new-app "Orders")
          [_ msgs] (recording-sub a)]
      (is (= 1 (count @msgs)))
      (is (= :snapshot (:op (first @msgs))))
      (is (contains? (:model (first @msgs)) :timelines)))))

(deftest snapshot-reflects-state-at-connect
  (testing "the snapshot carries the model as it is at connect time"
    (let [a (app/new-app "Orders")]
      (cmd/run a "create-timeline" {:title "Ordering"})
      (let [[_ msgs] (recording-sub a)
            snap     (first @msgs)]
        (is (= ["Ordering"] (map :title (get-in snap [:model :timelines]))))))))

(deftest delta-per-mutation
  (testing "DeltaPerMutation: each ModelAuthoring mutation yields exactly one delta"
    (let [a (app/new-app "Orders")
          [_ msgs] (recording-sub a)] ; msgs starts with the snapshot
      (cmd/run a "create-timeline" {:title "Ordering"})
      (cmd/run a "create-swimlane" {:name "Lane"})
      (is (= 3 (count @msgs)))                       ; snapshot + 2 deltas
      (is (= [:snapshot :CreateTimeline :CreateSwimlane] (map :op @msgs))))))

(deftest cascade-is-one-delta
  (testing "a cascading delete is one surface op -> one delta"
    (let [a (app/new-app "Orders")
          tl (:result (cmd/run a "create-timeline" {:title "T"}))
          _  (cmd/run a "add-slice" {:timeline (:id tl) :title "S" :kind "state_change" :index 0})
          [_ msgs] (recording-sub a)]
      (cmd/run a "delete-timeline" {:timeline (:id tl)})
      (is (= 2 (count @msgs)))                        ; snapshot + 1 delta
      (is (= :DeleteTimeline (:op (last @msgs))))
      (is (> (count (:changes (last @msgs))) 1)))))   ; carrying multiple changes

(deftest rejected-mutation-emits-no-delta
  (testing "a rejected mutation changes nothing and broadcasts nothing"
    (let [a (app/new-app "Orders")
          tl (:result (cmd/run a "create-timeline" {:title "T"}))
          _  (cmd/run a "add-slice" {:timeline (:id tl) :title "S" :kind "state_change" :index 0})
          slice-id (:id (first (m/slices (app/store a) (:id tl))))
          [_ msgs] (recording-sub a)
          res (cmd/run a "set-slice-status" {:slice slice-id :new-status "done"})]
      (is (r/error? res))
      (is (= 1 (count @msgs))))))                     ; only the snapshot

(deftest deltas-broadcast-to-all-subscribers-in-order
  (testing "every active subscription receives every delta, in commit order"
    (let [a (app/new-app "Orders")
          [_ m1] (recording-sub a)
          [_ m2] (recording-sub a)]
      (cmd/run a "create-timeline" {:title "A"})
      (cmd/run a "create-timeline" {:title "B"})
      (is (= [:CreateTimeline :CreateTimeline] (map :op (rest @m1))))
      (is (= (map :op @m1) (map :op @m2))))))

(deftest unsubscribe-stops-delivery
  (let [a (app/new-app "Orders")
        [sid msgs] (recording-sub a)]
    (app/unsubscribe! a sid)
    (is (zero? (app/subscriber-count a)))
    (cmd/run a "create-timeline" {:title "A"})
    (is (= 1 (count @msgs)))))                         ; only the snapshot, no delta

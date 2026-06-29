(ns emcli.app
  "The running application state shared by the two surfaces. A single process
  holds one EventModel in memory together with the set of active change-stream
  subscribers. Every authoring mutation (ModelAuthoring) is applied here and the
  resulting delta is broadcast, in commit order, to every active Subscription
  (ModelChangeStream): this is where DeltaPerMutation and SnapshotThenDeltas are
  enforced.

  An app is a plain atom; functions take it explicitly so it is trivially
  testable without a server or sockets."
  (:require [emcli.model :as m]
            [emcli.rules :as r]))

(defn new-app
  "Create an app holding a fresh model named `name`. Returns the atom."
  [name]
  (let [{:keys [store result]} (r/create-model (m/empty-store) {:name name})]
    (atom {:store store :model (:id result) :subscribers {} :lock (Object.)})))

(defn store [app] (:store @app))
(defn model-id [app] (:model @app))

;; ---------------------------------------------------------------------------
;; Canonical snapshot (ModelChangeStream `exposes` — the canonical shape, NOT
;; the eventmodeling.schema.json projection; CanonicalShape).
;; ---------------------------------------------------------------------------

(defn snapshot
  "A full canonical snapshot of the model: timelines/slices/placements,
  swimlanes and connections, by name."
  [app]
  (let [s   (store app)
        mid (model-id app)]
    {:op    :snapshot
     :model {:name (:name (m/fetch s :event-model mid))
             :timelines (for [t (m/timelines s mid)]
                          {:title (:title t)
                           :slices (for [sl (m/slices s (:id t))]
                                     {:title (:title sl) :kind (:kind sl)
                                      :status (:status sl) :index (:index sl)
                                      :placements (for [p (m/placements s (:id sl))
                                                        :let [el (m/placement-element s p)]]
                                                    {:name (:name el) :kind (:kind el)})})})
             :swimlanes   (map :name (m/swimlanes s mid))
             :connections (for [c (m/connections s mid)]
                            {:from (:name (m/fetch s :element (:from c)))
                             :to   (:name (m/fetch s :element (:to c)))})}}))

;; ---------------------------------------------------------------------------
;; Subscriber registry
;; ---------------------------------------------------------------------------

(defn- broadcast! [app delta]
  (doseq [send-fn (vals (:subscribers @app))]
    (send-fn delta)))

(defn apply-rule!
  "Apply an authoring rule. On success commit the new store and broadcast its
  single delta to every active subscriber (DeltaPerMutation). On rejection
  nothing changes. Returns the rule result map either way."
  [app rule-fn args]
  (locking (:lock @app)
    (let [res (rule-fn (store app) args)]
      (when-not (r/error? res)
        (swap! app assoc :store (:store res))
        (broadcast! app (:delta res)))
      res)))

(defn subscribe!
  "Register a change-stream subscriber. `send-fn` is called with each message.
  The subscriber is created via the Subscribe rule, then immediately sent the
  current snapshot; from then on it receives a delta per mutation. There is no
  gap: registration, the Subscribe delta and the snapshot are serialised against
  apply-rule! by the app lock (SnapshotThenDeltas). Returns the subscription id."
  [app send-fn]
  (locking (:lock @app)
    (let [res (r/subscribe (store app) {:model (model-id app)})
          sub (:result res)]
      (swap! app assoc :store (:store res))
      ;; the new subscriber's first message is the snapshot...
      (send-fn (snapshot app))
      ;; ...and only then is it wired up to receive subsequent deltas. No
      ;; Subscribe delta is broadcast: subscriptions are not part of the
      ;; canonical shape clients visualise (CanonicalShape), and only
      ;; ModelAuthoring mutations produce deltas (DeltaPerMutation).
      (swap! app assoc-in [:subscribers (:id sub)] send-fn)
      (:id sub))))

(defn unsubscribe!
  "Remove a subscriber via the Unsubscribe rule and stop sending to it. Like
  Subscribe, this emits no delta to other clients."
  [app sub-id]
  (locking (:lock @app)
    (let [res (r/unsubscribe (store app) {:subscription sub-id})]
      (when-not (r/error? res)
        (swap! app update :subscribers dissoc sub-id)
        (swap! app assoc :store (:store res)))
      res)))

(defn subscriber-count [app] (count (:subscribers @app)))

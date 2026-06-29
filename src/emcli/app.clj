(ns emcli.app
  "The running application state shared by the two surfaces. A single process
  holds one EventModel in memory together with the set of active change-stream
  subscribers. Every authoring mutation (ModelAuthoring) is applied here and the
  resulting delta is broadcast, in commit order, to every active Subscription
  (ModelChangeStream): this is where DeltaPerMutation and SnapshotThenDeltas are
  enforced.

  An app is a plain atom; functions take it explicitly so it is trivially
  testable without a server or sockets.

  When an app is opened with a `:file`, the canonical store is flushed to that
  EDN file after every committed mutation, crash-safely (write a temp file,
  fsync it, then atomically rename it over the target). On startup the store is
  reloaded from the file if it exists, so the model survives a crash. Runtime
  change-stream subscriptions are NOT persisted (existence = connected): they
  are cleared on save and start empty after a reload."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [emcli.model :as m]
            [emcli.rules :as r])
  (:import [java.nio.channels FileChannel]
           [java.nio.file CopyOption Files StandardCopyOption StandardOpenOption]))

(defn store [app] (:store @app))
(defn model-id [app] (:model @app))

;; ---------------------------------------------------------------------------
;; Persistence (crash-safe EDN flush on every write)
;; ---------------------------------------------------------------------------

(defn- persistable
  "The serialisable subset of the app: the model id and the store with runtime
  subscriptions cleared. The :seq counter is kept so ids never get reused."
  [app-val]
  {:model (:model app-val)
   :store (assoc (:store app-val) :subscriptions {})})

(defn persist!
  "Flush the current state to the app's EDN file, durably. No-op without a file.
  Writes a temp file, fsyncs it, then atomically renames it over the target, so
  a crash leaves either the previous complete file or the new one — never a
  partial write. Callers hold the app lock, so writes never interleave."
  [app]
  (when-let [file (:file @app)]
    (let [tmp     (str file ".tmp")
          tmp-path (.toPath (io/file tmp))
          target   (.toPath (io/file file))]
      (spit tmp (pr-str (persistable @app)))
      (with-open [ch (FileChannel/open tmp-path (into-array StandardOpenOption [StandardOpenOption/WRITE]))]
        (.force ch true))
      (Files/move tmp-path target
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                          StandardCopyOption/REPLACE_EXISTING])))))

(defn new-app
  "Create an app holding a fresh model named `name`. With `file`, the store is
  persisted there on every write (and the initial empty model is flushed now)."
  ([name] (new-app name nil))
  ([name file]
   (let [{:keys [store result]} (r/create-model (m/empty-store) {:name name})
         app (atom {:store store :model (:id result) :subscribers {} :lock (Object.) :file file})]
     (persist! app)
     app)))

(defn load-app
  "Rehydrate an app from an EDN `file` previously written by persist!. Runtime
  subscriptions and the broadcast lock are reconstructed fresh."
  [file]
  (let [{:keys [store model]} (edn/read-string (slurp file))]
    (atom {:store (assoc store :subscriptions {}) :model model
           :subscribers {} :lock (Object.) :file file})))

(defn open-app
  "Open the app backed by `file`: load it if the file exists, otherwise create a
  fresh model named `name` persisted to `file`. With no file, just a fresh app."
  ([name] (new-app name))
  ([name file]
   (if (and file (.exists (io/file file)))
     (load-app file)
     (new-app name file))))

;; ---------------------------------------------------------------------------
;; Canonical snapshot (ModelChangeStream `exposes` — the canonical shape, NOT
;; the eventmodeling.schema.json projection; CanonicalShape).
;; ---------------------------------------------------------------------------

(defn snapshot
  "A full canonical snapshot of the model: timelines/slices/placements,
  swimlanes and connections.

  Every entity carries its integer `id` — the same surrogate identity the deltas
  use — so a consumer can seed a normalised store from the snapshot and then
  patch it by id from subsequent deltas. The denormalised display fields the
  ModelChangeStream surface exposes (element/connection names and kinds) are
  nested under their sub-entity, mirroring the spec's `p.element.name` /
  `c.from.name` navigation."
  [app]
  (let [s   (store app)
        mid (model-id app)]
    {:op    :snapshot
     :model {:id   mid
             :name (:name (m/fetch s :event-model mid))
             :timelines (for [t (m/timelines s mid)]
                          {:id (:id t) :title (:title t)
                           :slices (for [sl (m/slices s (:id t))]
                                     {:id (:id sl) :title (:title sl) :kind (:kind sl)
                                      :status (:status sl) :index (:index sl)
                                      :placements (for [p (m/placements s (:id sl))
                                                        :let [el (m/placement-element s p)]]
                                                    {:id (:id p)
                                                     :element {:id (:id el) :name (:name el) :kind (:kind el)
                                                               :is_information_complete (m/information-complete? s el)}})})})
             :swimlanes   (for [sw (m/swimlanes s mid)]
                            {:id (:id sw) :name (:name sw)})
             :connections (for [c (m/connections s mid)
                                :let [from (m/fetch s :element (:from c))
                                      to   (m/fetch s :element (:to c))]]
                            {:id (:id c)
                             :from {:id (:id from) :name (:name from)}
                             :to   {:id (:id to)   :name (:name to)}
                             :derivations (for [d (:derivations c)]
                                            {:target_field (:target_field d)
                                             :source_fields (vec (:source_fields d))})})}}))

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
        ;; persist before broadcasting: a client must never observe a delta
        ;; that did not survive to disk.
        (persist! app)
        (broadcast! app (:delta res)))
      res)))

(defn replace-model!
  "Replace the entire model (used by import). Persists and re-snapshots every
  active subscriber. Runs under the app lock."
  [app new-store new-model]
  (locking (:lock @app)
    (swap! app assoc :store (assoc new-store :subscriptions {}) :model new-model)
    (persist! app)
    (let [snap (snapshot app)]
      (doseq [send-fn (vals (:subscribers @app))] (send-fn snap)))))

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

(ns emcli.completeness-test
  "Information-completeness obligations: FieldDerivation / FieldOrigin values,
  Connection.derivations, Element.field_origins, the SetConnectionDerivations /
  SetFieldOrigins rules, and the Element.is_information_complete derived
  predicate (carried / derived / introduced, strict field scope)."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.model :as m]
            [emcli.rules :as r]
            [emcli.support :as s]))

;; --- defaults --------------------------------------------------------------

(deftest new-list-fields-default-empty
  (let [[store mid] (s/with-model)
        el  (:result (s/ok store r/create-element {:model mid :name "E" :kind :event}))]
    (is (= [] (:field_origins el)))
    (let [store (:store (s/ok store r/create-element {:model mid :name "E" :kind :event}))
          a     (:id (first (m/elements store mid)))
          store (:store (s/ok store r/create-element {:model mid :name "B" :kind :command}))
          b     (:id (second (m/elements store mid)))
          c     (:result (s/ok store r/connect {:from b :to a}))]
      (is (= [] (:derivations c))))))

;; --- rules -----------------------------------------------------------------

(defn- two-elements [kind-from kind-to]
  (let [[store mid] (s/with-model)
        store (:store (s/ok store r/create-element {:model mid :name "Src" :kind kind-from}))
        from  (:id (first (m/elements store mid)))
        store (:store (s/ok store r/create-element {:model mid :name "Dst" :kind kind-to}))
        to    (:id (second (m/elements store mid)))]
    [store mid from to]))

(deftest set-field-origins-rule
  (let [[store mid] (s/with-model)
        store (:store (s/ok store r/create-element {:model mid :name "Cmd" :kind :command}))
        eid   (:id (first (m/elements store mid)))
        {:keys [delta result]} (s/ok store r/set-field-origins
                                     {:element eid :origins [{:field "id" :origin :user_input}]})]
    (is (= :SetFieldOrigins (:op delta)))
    (is (= [{:field "id" :origin :user_input}] (:field_origins result)))))

(deftest set-connection-derivations-rule
  (let [[store mid from to] (two-elements :event :read_model)
        store (:store (s/ok store r/connect {:from from :to to}))
        cid   (:id (first (m/connections store mid)))
        {:keys [delta result]} (s/ok store r/set-connection-derivations
                                     {:connection cid
                                      :derivations [{:target_field "total" :source_fields ["amount"]}]})]
    (is (= :SetConnectionDerivations (:op delta)))
    (is (= [{:target_field "total" :source_fields ["amount"]}] (:derivations result)))))

;; --- is_information_complete: the three provenance kinds -------------------

(defn- with-field [store eid fname]
  (m/set-field store :element eid :fields
               (conj (vec (:fields (m/fetch store :element eid)))
                     {:name fname :type :string :optional false :cardinality :single :subfields []})))

(deftest carried-field-is-sourced
  (testing "a target field is sourced when the source carries a same-named field"
    (let [[store mid from to] (two-elements :command :event)
          store (with-field store from "id")
          store (with-field store to "id")
          store (:store (s/ok store r/connect {:from from :to to}))
          dst   (m/fetch store :element to)]
      (is (m/information-complete? store dst))
      (is (empty? (m/unsourced-fields store dst))))))

(deftest unsourced-field-makes-incomplete
  (testing "STRICT scope: a field with no source leaves the element incomplete"
    (let [[store mid from to] (two-elements :command :event)
          store (with-field store from "id")
          store (with-field store to "id")
          store (with-field store to "at")           ; not on the source
          store (:store (s/ok store r/connect {:from from :to to}))
          dst   (m/fetch store :element to)]
      (is (not (m/information-complete? store dst)))
      (is (= ["at"] (m/unsourced-fields store dst))))))

(deftest derived-field-is-sourced
  (testing "a renamed/aggregated field is sourced via a connection derivation"
    (let [[store mid from to] (two-elements :event :read_model)
          store (with-field store from "amount")
          store (with-field store to "total")        ; aggregated from amount
          store (:store (s/ok store r/connect {:from from :to to}))
          cid   (:id (first (m/connections store mid)))
          dst   (m/fetch store :element to)]
      (is (not (m/information-complete? store dst)) "incomplete before the derivation")
      (let [store (:store (s/ok store r/set-connection-derivations
                                {:connection cid :derivations [{:target_field "total" :source_fields ["amount"]}]}))
            dst   (m/fetch store :element to)]
        (is (m/information-complete? store dst) "complete once derived from a real source field")))))

(deftest derivation-with-missing-source-does-not-source
  (testing "an orphaned derivation (unknown source field) fails to source its target"
    (let [[store mid from to] (two-elements :event :read_model)
          store (with-field store to "total")        ; source has NO 'amount'
          store (:store (s/ok store r/connect {:from from :to to}))
          cid   (:id (first (m/connections store mid)))
          store (:store (s/ok store r/set-connection-derivations
                              {:connection cid :derivations [{:target_field "total" :source_fields ["amount"]}]}))
          dst   (m/fetch store :element to)]
      (is (not (m/information-complete? store dst)))
      (is (= ["total"] (m/unsourced-fields store dst))))))

(deftest introduced-field-is-sourced
  (testing "a field-origin override marks a field as legitimately introduced"
    (let [[store mid] (s/with-model)
          store (:store (s/ok store r/create-element {:model mid :name "PlaceOrder" :kind :command}))
          eid   (:id (first (m/elements store mid)))
          store (with-field store eid "id")          ; user-entered, no upstream
          dst   (m/fetch store :element eid)]
      (is (not (m/information-complete? store dst)) "incomplete before the override")
      (let [store (:store (s/ok store r/set-field-origins {:element eid :origins [{:field "id" :origin :user_input}]}))
            dst   (m/fetch store :element eid)]
        (is (m/information-complete? store dst))))))

(deftest aggregation-spans-multiple-incoming-connections
  (testing "a read model field set is covered by the union of its inbound connections"
    (let [[store mid] (s/with-model)
          mk    (fn [s name kind] (let [s (:store (s/ok s r/create-element {:model mid :name name :kind kind}))]
                                    [s (:id (last (m/elements s mid)))]))
          [store e1] (mk store "OrderPlaced" :event)
          [store e2] (mk store "ShippingSet" :event)
          [store rm] (mk store "Summary" :read_model)
          store (with-field store e1 "subtotal")
          store (with-field store e2 "shipping")
          store (with-field store rm "subtotal")     ; carried from e1
          store (with-field store rm "total")        ; derived from e2.shipping (renamed/aggregated)
          store (:store (s/ok store r/connect {:from e1 :to rm}))
          store (:store (s/ok store r/connect {:from e2 :to rm}))
          c2    (:id (second (m/connections store mid)))
          store (:store (s/ok store r/set-connection-derivations
                              {:connection c2 :derivations [{:target_field "total" :source_fields ["shipping"]}]}))
          dst   (m/fetch store :element rm)]
      (is (m/information-complete? store dst)
          "subtotal carried via connection 1, total derived via connection 2"))))

;; --- ValidateModel reporting ----------------------------------------------

(deftest validate-reports-incomplete-and-orphaned
  (let [a   (app/new-app "M")
        mid (app/model-id a)
        from (:result (cmd/run a "create-element" {:name "Evt" :kind "event"}))
        to   (:result (cmd/run a "create-element" {:name "RM" :kind "read_model"}))]
    (cmd/run a "set-fields" {:element (:id to) :fields [{:name "total" :type :decimal :optional false :cardinality :single :subfields []}]})
    (cmd/run a "connect" {:from (:id from) :to (:id to)})
    (let [cid (:id (first (m/connections (app/store a) mid)))]
      (cmd/run a "set-connection-derivations" {:connection cid :derivations [{:target_field "total" :source_fields ["amount"]}]})
      (let [report (cmd/validate a)]
        (is (some #(= (:id to) (:element %)) (:incomplete-elements report)))
        (is (= ["total"] (:unsourced (first (filter #(= (:id to) (:element %)) (:incomplete-elements report))))))
        (is (seq (:orphaned-derivations report)))
        (is (= ["amount"] (:missing_source_fields (first (:orphaned-derivations report)))))))))

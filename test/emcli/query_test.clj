(ns emcli.query-test
  "ModelQuery (event-model.allium): the read-only structural query engine and
  its command boundary. Each test maps to an obligation or invariant of the
  ModelQuery contract (relation vocabulary, association handling, the closed
  stage set, bounded results, read-only, and self-correcting rejection)."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.app :as app]
            [emcli.commands :as cmd]
            [emcli.query :as q]))

(defn- rejects?
  "True if `thunk` throws an ExceptionInfo whose message matches `re`."
  [re thunk]
  (try (thunk) false
       (catch clojure.lang.ExceptionInfo e
         (boolean (re-find re (or (ex-message e) ""))))))

(defn- build
  "A small but representative model: one timeline with two slices, three
  elements, a swimlane, a command->event connection (with a derivation) and a
  specification with one step."
  []
  (let [a   (app/new-app "M")
        tl  (:id (:result (cmd/run a "create-timeline" {:title "Checkout"})))
        lane (:id (:result (cmd/run a "create-swimlane" {:name "Actor" :index 0})))
        sl1 (:id (:result (cmd/run a "add-slice" {:timeline tl :title "Ordering" :slice-type "state_change" :index 0})))
        sl2 (:id (:result (cmd/run a "add-slice" {:timeline tl :title "Confirm" :slice-type "state_change" :index 1})))
        e1  (:id (:result (cmd/run a "create-element" {:name "PlaceOrder" :element-type "command"})))
        e2  (:id (:result (cmd/run a "create-element" {:name "OrderPlaced" :element-type "event"})))
        e3  (:id (:result (cmd/run a "create-element" {:name "OrderView" :element-type "read_model"})))]
    (cmd/run a "add-field" {:element e1 :name "id" :type "string"})
    (cmd/run a "add-field" {:element e2 :name "id" :type "string"})
    (cmd/run a "assign-swimlane" {:element e1 :lane lane})
    (cmd/run a "place-element" {:slice sl1 :element e1})
    (cmd/run a "place-element" {:slice sl1 :element e2})
    (cmd/run a "place-element" {:slice sl2 :element e3})
    (let [c  (:id (:result (cmd/run a "connect" {:from e1 :to e2})))]
      (cmd/run a "add-derivation" {:connection c :target "id" :from "id"})
      (let [sp (:id (:result (cmd/run a "add-specification" {:slice sl1 :title "spec"})))
            st (:id (:result (cmd/run a "add-spec-step" {:spec sp :clause "when_step" :element e1 :index 0})))]
        {:app a :tl tl :lane lane :sl1 sl1 :sl2 sl2 :e1 e1 :e2 e2 :e3 e3 :c c :sp sp :st st}))))

(defn- run-q [env s] (cmd/query-model (:app env) s))
(defn- ids [rows] (set (map :id rows)))

;; --- roots -----------------------------------------------------------------

(deftest model-is-implicit-context
  (let [env (build)]
    (testing "the model is never a root"
      (is (rejects? #"the model is the implicit context" #(run-q env "model"))))
    (testing "the six entity kinds are roots"
      (doseq [r ["timeline" "swimlane" "slice" "element" "specification" "step"]]
        (is (vector? (run-q env r)) (str "root " r " is queryable"))))
    (testing "plural spellings normalise to the kind"
      (is (= (run-q env "slice") (run-q env "slices"))))))

(deftest associations-are-never-roots
  (let [env (build)]
    (doseq [r ["placement" "placements" "connection" "connections"]]
      (is (rejects? #"roots are" #(run-q env r)) (str r " is not a root")))))

;; --- relation vocabulary ---------------------------------------------------

(deftest relation-vocabulary-is-bidirectional
  (let [env (build) {:keys [tl lane sl1 sl2 e1 e2 sp st]} env]
    (testing "containment, forward and reverse (every FK traversable both ways)"
      (is (= #{sl1 sl2} (ids (run-q env (str "timeline:" tl " | slice")))))
      (is (= #{tl}      (ids (run-q env (str "slice:" sl1 " | timeline")))))
      (is (= #{sp}      (ids (run-q env (str "slice:" sl1 " | specification")))))
      (is (= #{sl1}     (ids (run-q env (str "specification:" sp " | slice")))))
      (is (= #{st}      (ids (run-q env (str "specification:" sp " | step")))))
      (is (= #{sp}      (ids (run-q env (str "step:" st " | specification")))))
      (is (= #{e1}      (ids (run-q env (str "step:" st " | element")))))
      (is (= #{st}      (ids (run-q env (str "element:" e1 " | step")))))
      (is (= #{lane}    (ids (run-q env (str "element:" e1 " | swimlane")))))
      (is (= #{e1}      (ids (run-q env (str "swimlane:" lane " | element"))))))
    (testing "slice <-> element via placement"
      (is (= #{e1 e2} (ids (run-q env (str "slice:" sl1 " | element")))))
      (is (= #{sl1}   (ids (run-q env (str "element:" e1 " | slice"))))))
    (testing "element -> element via connection (directional)"
      (is (= #{e2} (ids (run-q env (str "element:" e1 " | outgoing")))))
      (is (= #{e1} (ids (run-q env (str "element:" e2 " | incoming"))))))
    (testing "an element in several slices is reached in all of them"
      (is (= #{sl1} (ids (run-q env (str "element:" e1 " | slice"))))))))

;; --- edge projection -------------------------------------------------------

(deftest edge-attributes-require-projection
  (let [env (build) {:keys [sl1 e1]} env]
    (testing "without projection a row carries only the landed entity"
      (is (nil? (:placement (first (run-q env (str "slice:" sl1 " | element")))))))
    (testing "an explicit projection attaches the placement under its key"
      (let [rows (run-q env (str "slice:" sl1 " | elements {index}"))]
        (is (every? #(contains? (:placement %) :index) rows))))
    (testing "the whole edge is available with {}"
      (let [p (:placement (first (run-q env (str "slice:" sl1 " | elements {}"))))]
        (is (= #{:slice :element :index} (set (keys p))))))
    (testing "connection derivations project through the connection edge"
      (let [c (:connection (first (run-q env (str "element:" e1 " | outgoing {derivations}"))))]
        (is (= [{:target_field "id" :source_fields ["id"]}] (:derivations c)))))
    (testing "a containment relation has nothing to project"
      (is (rejects? #"nothing to project" #(run-q env "timeline | slice {index}"))))))

;; --- the closed stage set --------------------------------------------------

(deftest stages-are-closed
  (let [env (build) {:keys [sl1 e1 e2 e3]} env]
    (testing "where: =, !=, ~ (regex), in (set membership)"
      (is (= #{e1}    (ids (run-q env "element | where element_type=command"))))
      (is (= #{e1 e3} (ids (run-q env "element | where element_type!=event"))))
      (is (= #{e2}    (ids (run-q env "element | where name~Placed"))))
      (is (= #{e1 e2} (ids (run-q env "element | where element_type in (command,event)")))))
    (testing "order: ascending and descending"
      (is (= ["OrderPlaced" "OrderView" "PlaceOrder"] (map :name (run-q env "element | order name"))))
      (is (= ["PlaceOrder" "OrderView" "OrderPlaced"] (map :name (run-q env "element | order -name")))))
    (testing "select narrows a row"
      (is (= #{:id :name} (set (keys (first (run-q env "element | where element_type=command | select id,name")))))))
    (testing "count collapses to a scalar"
      (is (= 2 (run-q env (str "slice:" sl1 " | element | count")))))
    (testing "limit caps the rows"
      (is (= 2 (count (run-q env "element | limit 2")))))
    (testing "distinct removes duplicates from a fan-out"
      (is (= 2 (count (run-q env (str "slice:" sl1 " | element | slice")))))
      (is (= 1 (count (run-q env (str "slice:" sl1 " | element | slice | distinct"))))))))

;; --- result shape ----------------------------------------------------------

(deftest results-are-bounded-rows
  (let [env (build) {:keys [sl1 e1]} env]
    (testing "a row carries identity, name and a location breadcrumb"
      (let [r (first (run-q env (str "slice:" sl1)))]
        (is (= :slice (:kind r)))
        (is (= "Ordering" (:name r)))
        (is (= "Checkout" (get-in r [:breadcrumb :timeline_title])))))
    (testing "an element row carries its swimlane in the breadcrumb"
      (is (= "Actor" (get-in (first (run-q env (str "element:" e1))) [:breadcrumb :swimlane_name]))))))

(deftest read-only
  (let [env    (build)
        a      (:app env)
        before (app/store a)]
    (run-q env "element | slice | specification | step")
    (is (= before (app/store a)) "no rule fired; the model is unchanged")))

;; --- rejection with alternatives -------------------------------------------

(deftest unknown-step-rejected-with-alternatives
  (let [env (build)]
    (testing "element -> element needs a direction, and the error says so"
      (is (rejects? #"outgoing" #(run-q env "element:1 | element"))))
    (testing "a relation that does not exist names the valid ones"
      (is (rejects? #"no relation from timeline" #(run-q env "timeline | element"))))
    (testing "an unknown token is rejected"
      (is (rejects? #"unknown relation" #(run-q env "element | frobnicate"))))))

;; --- name roots delegate to NameResolution ---------------------------------

(deftest name-roots-delegate-to-nameresolution
  (let [env (build) {:keys [e1 tl]} env]
    (testing "an exact name root resolves"
      (is (= [e1] (map :id (run-q env "element:\"PlaceOrder\"")))))
    (testing "and composes with relations"
      (is (= #{"PlaceOrder" "OrderPlaced"} (set (map :name (run-q env "slice:\"Ordering\" | element"))))))
    (testing "a name root honours the declared kind"
      (is (= [tl] (map :id (run-q env "timeline:\"Checkout\""))))
      (is (rejects? #"no element named \"Checkout\"; another kind has that name"
                    #(run-q env "element:\"Checkout\""))))
    (testing "and the row is the entity itself, so stages see its real fields"
      (is (= [{:id e1 :kind :element :name "PlaceOrder"}]
             (map #(select-keys % [:id :kind :name]) (run-q env "element:\"PlaceOrder\""))))
      (is (= [e1] (map :id (run-q env "element:\"PlaceOrder\" | where element_type=command"))))
      (is (= [{:swimlane_name "Actor"}]
             (map :breadcrumb (run-q env "element:\"PlaceOrder\"")))))
    (testing "a root that names nothing is rejected, never resolved to a near miss"
      ;; the near-miss tier is resolve's did-you-mean affordance; taking it as a
      ;; root would be the implicit best pick NoImplicitBestPick forbids
      (is (rejects? #"no element named \"PlaceOrdr\"; nearest of that kind: PlaceOrder"
                    #(run-q env "element:\"PlaceOrdr\"")))
      (is (rejects? #"no swimlane named \"Nothing\"" #(run-q env "swimlane:\"Nothing\""))))
    (testing "an id root that names nothing is rejected too"
      (is (rejects? #"no timeline with id 999" #(run-q env "timeline:999"))))))

(deftest an-ambiguous-name-root-is-rejected
  (testing "a name two slices share is not silently resolved to one of them"
    ;; slice titles are unique per timeline, not per model, so this is legal and
    ;; only the id can disambiguate
    (let [a  (app/new-app "M")
          t1 (:id (:result (cmd/run a "create-timeline" {:title "T1"})))
          t2 (:id (:result (cmd/run a "create-timeline" {:title "T2"})))
          s1 (:id (:result (cmd/run a "add-slice" {:timeline t1 :title "Ordering"
                                                   :slice-type "state_change" :index 0})))]
      (cmd/run a "add-slice" {:timeline t2 :title "Ordering" :slice-type "state_change" :index 0})
      (is (rejects? #"several slices are named \"Ordering\""
                    #(cmd/query-model a "slice:\"Ordering\"")))
      (is (= [s1] (map :id (cmd/query-model a (str "slice:" s1))))
          "the id root is the caller's disambiguator"))))

(deftest an-ambiguous-name-root-says-when-the-list-is-capped
  (testing "a capped candidate list is never presented as exhaustive"
    (let [a (app/new-app "M")]
      (dotimes [i 6]
        (let [tl (:id (:result (cmd/run a "create-timeline" {:title (str "T" i)})))]
          (cmd/run a "add-slice" {:timeline tl :title "Ordering" :slice-type "state_change" :index 0})))
      (let [msg (try (cmd/query-model a "slice:\"Ordering\"") nil
                     (catch Exception e (ex-message e)))]
        (is (re-find #"several slices are named" msg))
        (is (re-find #"capped" msg))))))

(deftest a-step-root-cannot-be-named
  (let [env (build)]
    (is (rejects? #"a step has no name to root on" #(run-q env "step:\"Any title\"")))
    (is (vector? (run-q env "step")) "by kind it still roots")))

(deftest keyword-case-never-changes-the-question
  (let [env (build)]
    (testing "a comparator word is case-insensitive, like the other keywords"
      (is (= (ids (run-q env "element | where element_type in (command,event)"))
             (ids (run-q env "ELEMENT | WHERE element_type IN command,event")))))
    (testing "a field name is not: an unknown field is rejected, not silently empty"
      (is (rejects? #"KIND" #(run-q env "element | where KIND=command"))))))

;; --- value types, enums, and introspection ---------------------------------

(deftest parsed-shape-and-value-equality
  (testing "QueryRoot: kind, optional id / name"
    (is (= {:kind :element} (:root (q/parse-query "element"))))
    (is (= {:kind :element :id 42} (:root (q/parse-query "element:42"))))
    (is (= {:kind :slice :name "Ordering"} (:root (q/parse-query "slice:\"Ordering\"")))))
  (testing "QueryStage: a stage kind plus its operands"
    (is (= {:kind :follow :target :slice :direction nil :projection nil}
           (last (:stages (q/parse-query "element | slice")))))
    (is (= {:kind :follow :target :element :direction :outgoing :projection nil}
           (last (:stages (q/parse-query "element | outgoing")))))
    (is (= {:kind :where :field "element_type" :comparator :equals :operand "command"}
           (last (:stages (q/parse-query "element | where element_type=command")))))
    (is (= {:kind :where :field "element_type" :comparator :in_set :operand ["command" "event"]}
           (last (:stages (q/parse-query "element | where element_type in (command,event)")))))
    (is (= {:kind :order :field "name" :descending true}
           (last (:stages (q/parse-query "element | order -name")))))
    (is (= {:kind :select :fields ["id" "name"]}
           (last (:stages (q/parse-query "element | select id,name")))))
    (is (= {:kind :limit :limit 3} (last (:stages (q/parse-query "element | limit 3")))))
    (is (= {:kind :count} (last (:stages (q/parse-query "element | count")))))
    (is (= {:kind :distinct} (last (:stages (q/parse-query "element | distinct"))))))
  (testing "QueryExpression is a value: equal when identical, unequal otherwise"
    (is (= (q/parse-query "element | where element_type=command")
           (q/parse-query "element | where element_type=command")))
    (is (not= (q/parse-query "element | where element_type=command")
              (q/parse-query "element | where element_type=event"))))
  (testing "comparators are a closed enum"
    (is (= #{:equals :not_equals :matches}
           (set (map #(:comparator (last (:stages (q/parse-query (str "element | where name" % "x")))))
                     ["=" "!=" "~"]))))
    (is (= :in_set (:comparator (last (:stages (q/parse-query "element | where element_type in (a)"))))))))

(deftest relations-doc-introspection
  (let [d (q/relations-doc)]
    (testing "roots exclude the model and the associations"
      (is (= ["timeline" "swimlane" "slice" "element" "specification" "step"] (:roots d)))
      (is (not-any? #(#{"placement" "connection"} (:from %)) (:relations d))))
    (testing "the vocabulary lists the associations with their projections"
      (is (some #(and (= "slice" (:from %)) (= "element" (:to %))
                      (= "placement" (get-in % [:projection :key]))) (:relations d)))
      (is (some #(and (= "element" (:from %)) (= "element" (:to %)) (= "outgoing" (:name %))) (:relations d))))
    (testing "the stages are the closed set"
      (is (= ["where" "order" "select" "count" "limit" "distinct"] (:stages d))))
    (testing "the tool description is generated from the same vocabulary"
      (let [s (q/tool-description)]
        (is (re-find #"outgoing" s))
        (is (re-find #"where" s))))))

;; --- the closed field set (ProjectableFieldsAreClosed / UnknownFieldRejected) --

(deftest projectable-fields-are-closed
  (let [env (build)
        e1  (:e1 env) sl1 (:sl1 env)]
    (testing "an element row carries the vocabulary the element operations address a field by"
      (let [[row] (run-q env (str "element:" e1 " | select name,element_type,context,fields"))]
        (is (= "PlaceOrder" (:name row)))
        (is (= :command (:element_type row)) "the element's own type, not the category")
        (is (= :internal (:context row)))
        (is (= ["id"] (map :name (:fields row)))
            "the recursive Field list, so a caller can read a name before renaming one")))
    (testing "a stage may name the entity's declared attributes, not only the row's"
      (is (= ["Checkout"] (map :title (run-q env "timeline | select title"))))
      (is (= 2 (count (run-q env "slice | where status = created"))) "slice.status")
      (is (= ["Ordering"] (map :name (run-q env (str "slice:" sl1 " | select name"))))
          "a row's name is the slice's title")
      (is (= #{:when_step} (set (map :clause (run-q env "step | select clause"))))))
    (testing "a projected association is nameable under its own key"
      (let [[row] (run-q env (str "element:" e1 " | slice {index} | select name,placement"))]
        (is (= "Ordering" (:name row)))
        (is (= 0 (get-in row [:placement :index])))))
    (testing "kind is the entity CATEGORY; the element's own type is element_type"
      (is (= 3 (count (run-q env "element | where kind = element"))))
      (is (= ["PlaceOrder"] (map :name (run-q env "element | where element_type = command"))))
      (is (= [] (run-q env "element | where kind = command"))
          "a valid field that no row's value matches, not an unknown name"))
    (testing "a name the row cannot answer is rejected, naming what it does carry"
      (is (rejects? #"unknown field \"nosuchfield\" on element rows"
                    #(run-q env "element | select nosuchfield")))
      (is (rejects? #"unknown field \"nosuchfield\"" #(run-q env "element | where nosuchfield = x")))
      (is (rejects? #"unknown field \"nosuchfield\"" #(run-q env "element | order nosuchfield"))))
    (testing "the set is per kind: a name on one entity is refused on another"
      (is (rejects? #"\"fields\" on step rows" #(run-q env "step | select fields")))
      (is (rejects? #"\"status\" on element rows" #(run-q env "element | select status"))))))

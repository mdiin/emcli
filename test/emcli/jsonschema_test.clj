(ns emcli.jsonschema-test
  "Unit tests for the JSON-Schema subset validator. These prove the validator is
  not vacuous: each construct it must implement has a passing and a failing case,
  so the change-stream drift check cannot silently accept drift."
  (:require [clojure.test :refer [deftest testing is]]
            [emcli.jsonschema :as js]))

(defn- violations [schema data] (js/validate schema data))
(defn- valid?     [schema data] (empty? (violations schema data)))
(defn- reasons    [schema data] (mapv :message (violations schema data)))
(defn- pointers   [schema data] (mapv :pointer (violations schema data)))

(deftest type-and-nullable-unions
  (testing "a plain type"
    (is (valid? {"type" "string"} "x"))
    (is (not (valid? {"type" "string"} 1))))
  (testing "a nullable union accepts the value or null"
    (is (valid? {"type" ["string" "null"]} "x"))
    (is (valid? {"type" ["string" "null"]} nil))
    (is (not (valid? {"type" ["string" "null"]} 1))))
  (testing "integer and number are distinct from strings and each other"
    (is (valid? {"type" "integer"} 1))
    (is (valid? {"type" "number"} 1.5))
    (is (valid? {"type" "number"} 1))
    (is (not (valid? {"type" "integer"} 1.5)))
    (is (not (valid? {"type" "integer"} "1")))))

(deftest required-and-properties
  (let [schema {"type" "object"
                "required" ["a"]
                "additionalProperties" false
                "properties" {"a" {"type" "string"} "b" {"type" "integer"}}}]
    (is (valid? schema {"a" "x"}))
    (is (valid? schema {"a" "x" "b" 2}))
    (is (= ["missing required property \"a\""] (reasons schema {"b" 2})))
    (is (= ["expected type integer, found string"] (reasons schema {"a" "x" "b" "no"})))
    (is (some #(re-find #"unexpected property" %) (reasons schema {"a" "x" "c" 1})))))

(deftest additional-properties-as-a-schema
  (let [schema {"type" "object"
                "additionalProperties" {"type" ["string" "number"]}}]
    (is (valid? schema {"any" "x" "other" 3}))
    (is (some #(re-find #"expected type" %) (reasons schema {"any" true})))))

(deftest enum-and-const
  (is (valid? {"enum" ["a" "b"]} "a"))
  (is (some #(re-find #"expected one of" %) (reasons {"enum" ["a" "b"]} "c")))
  (is (valid? {"const" "snapshot"} "snapshot"))
  (is (some #(re-find #"expected \"snapshot\"" %) (reasons {"const" "snapshot"} "delta"))))

(deftest one-of-requires-exactly-one
  (let [schema {"oneOf" [{"type" "string"} {"type" "object"}]}]
    (is (valid? schema "x"))
    (is (valid? schema {"k" 1}))
    (is (not (valid? schema 5))))
  (testing "a value matching two shapes is rejected"
    (is (some #(re-find #"exactly one is required" %)
              (reasons {"oneOf" [{"type" "integer"} {"type" "number"}]} 1))))
  (testing "when none match, the closest branch's deep violations are surfaced"
    (let [schema {"oneOf" [{"type" "object" "required" ["deep"] "additionalProperties" false
                            "properties" {"deep" {"type" "string"}}}
                           {"type" "object" "required" ["x" "y"] "additionalProperties" false
                            "properties" {"x" {"type" "null"} "y" {"type" "null"}}}]}]
      (is (= ["missing required property \"deep\""] (reasons schema {}))))))

(deftest arrays-required
  (let [schema {"type" "array" "items" {"type" "integer"}}]
    (is (valid? schema [1 2 3]))
    (is (= ["/1"] (pointers schema [1 "x"])))
    (testing "cheshire parses JSON arrays as seqs, not vectors — both must validate"
      (is (valid? schema '(1 2 3)))
      (is (= ["/1"] (pointers schema '(1 "x")))))))

(deftest prefix-items-and-min-items
  (let [schema {"type" "array"
                "minItems" 2
                "prefixItems" [{"type" "string"}
                               {"type" "object" "required" ["-id"] "additionalProperties" false
                                "properties" {"-id" {"type" "string"}}}]
                "items" {"type" "string"}}]
    (is (valid? schema ["tag" {"-id" "n1"}]))
    (is (valid? schema ["tag" {"-id" "n1"} "text"]))
    (is (some #(re-find #"at least 2 items" %) (reasons schema ["tag"])))
    (is (some #(re-find #"unexpected property" %) (reasons schema ["tag" {"-id" "n1" "extra" 1}])))
    (is (= ["/2"] (pointers schema ["tag" {"-id" "n1"} 3])))))

(deftest ref-and-defs
  (let [schema {"$defs" {"Leaf" {"type" "object" "required" ["k"] "additionalProperties" false
                                "properties" {"k" {"type" "string"}}}}
                "oneOf" [{"$ref" "#/$defs/Leaf"} {"type" "null"}]}]
    (is (valid? schema {"k" "v"}))
    (is (valid? schema nil))
    (is (not (valid? schema {"nope" 1})))
    (testing "validate-ref targets a named $def directly"
      (is (empty? (js/validate-ref schema "#/$defs/Leaf" {"k" "v"})))
      (is (= ["missing required property \"k\""]
             (mapv :message (js/validate-ref schema "#/$defs/Leaf" {})))))))

(deftest pointers-locate-the-failure
  (let [schema {"type" "object"
                "required" ["a"]
                "additionalProperties" false
                "properties" {"a" {"type" "array" "items" {"type" "integer"}}}}]
    (is (= ["/a/1"] (pointers schema {"a" [1 "x"]})))
    (is (= [""] (pointers schema {})))))

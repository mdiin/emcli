(ns emcli.test-runner
  "Discovers and runs every *_test namespace under test/."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]))

(defn- test-namespaces []
  (->> (fs/glob "test" "**/*_test.clj")
       (map (fn [p]
              (-> (str (fs/relativize "test" p))
                  (str/replace #"\.clj$" "")
                  (str/replace #"/" ".")
                  (str/replace #"_" "-")
                  symbol)))
       sort))

(defn run [& _]
  (let [nss (test-namespaces)]
    (apply require nss)
    (let [{:keys [fail error]} (apply t/run-tests nss)]
      (System/exit (if (pos? (+ fail error)) 1 0)))))

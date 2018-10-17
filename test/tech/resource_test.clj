(ns tech.resource-test
  (:require [tech.resource :as resource]
            [clojure.test :refer :all]))

(deftest basic-resource-management
  (let [result-atom (atom 1)]
    (resource/with-resource-context
      (resource/make-resource #(swap! result-atom dec)))
    (is (= 0 @result-atom)))

  (let [result-atom (atom [])
        test-data (range 5)
        {resource-seq :resource-seq
         result :return-value}
        (resource/return-resource-seq
         (->> test-data
              (map (fn [idx]
                     (do (resource/make-resource
                          #(swap! result-atom conj idx))
                         idx)))
              doall))]
    (is (= (vec result) (vec test-data)))
    (is (= [] @result-atom))
    (resource/release-resource-seq resource-seq)
    ;;Resources are removed in reverse order.
    (is (= [4 3 2 1 0] @result-atom))))

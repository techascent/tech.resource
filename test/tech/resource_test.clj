(ns tech.resource-test
  (:require [tech.resource :as resource]
            [tech.resource.stack :as stack]
            [clojure.test :refer :all])
  (:import [java.io Closeable]))

(deftest basic-resource-management
  (let [result-atom (atom 1)]
    (resource/stack-resource-context
      (resource/track #(swap! result-atom dec)))
    (is (= 0 @result-atom)))

  (let [result-atom (atom [])
        test-data (range 5)
        {resource-seq :resource-seq
         result :return-value}
        (stack/return-resource-seq
         (->> test-data
              (map (fn [idx]
                     (do (resource/track
                          #(swap! result-atom conj idx))
                         idx)))
              doall))]
    (is (= (vec result) (vec test-data)))
    (is (= [] @result-atom))
    (stack/release-resource-seq resource-seq)
    ;;Resources are removed in reverse order.
    (is (= [4 3 2 1 0] @result-atom))))


(deftest java-closeable-are-resources
  (let [test-atom (atom 0)]
    (resource/stack-resource-context
     (resource/track (reify Closeable
                       (close [this]
                         (swap! test-atom inc)))))
    (is (= 1 @test-atom))))

(ns tech.v3.resource-test
  (:require [tech.v3.resource :as resource]
            [tech.v3.resource.stack :as stack]
            [clojure.test :refer [deftest is]])
  (:import [java.io Closeable]))

(deftest basic-resource-management
  (let [result-atom (atom 1)]
    (resource/stack-resource-context
     (resource/track #(swap! result-atom dec) {:track-type :stack}))
    (is (= 0 @result-atom)))

  (let [result-atom (atom [])
        test-data (range 5)
        {resource-seq :resource-seq
         result :return-value}
        (stack/return-resource-seq
         (->> test-data
              (map (fn [idx]
                     (resource/track
                      #(swap! result-atom conj idx)
                      {:track-type :stack})
                     idx))
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
                         (swap! test-atom inc)))
                     {:track-type :stack}))
    (is (= 1 @test-atom))))


(deftest auto-resource-context
  (is (= #{:gc} (resource/normalize-track-type :auto)))
  (resource/stack-resource-context
   (is (= #{:stack} (resource/normalize-track-type :auto)))))

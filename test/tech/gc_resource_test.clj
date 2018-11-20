(ns tech.gc_resource_test
  (:require [tech.gc-resource :as gc-resource]
            [tech.resource :as resource]
            [clojure.test :refer :all]))


(deftest gc-resources
  (testing "System.gc cleans up the things"
    (let [counter (atom 0)]
      (let [create-fn (fn []
                        (swap! counter inc)
                        (gc-resource/track (Object.) #(do
                                                        (swap! counter dec))))]
        (->> (repeatedly 100 #(create-fn))
             dorun)
        (is (= 100 @counter))
        (System/gc)
        (Thread/sleep 100)
        (is (= 0 @counter)))
      (System/gc)
      (is (= 0 @counter))))
  (testing "resource context and system.gc work together"
    (let [counter (atom 0)]
      (resource/with-resource-context
        (let [create-fn (fn []
                          (swap! counter inc)
                          (gc-resource/track (Object.) #(swap! counter dec)))
              objects (vec (repeatedly 100 #(create-fn)))]
          (is (= 100 @counter))
          (System/gc)
          (Thread/sleep 100)
          (is (= 100 @counter))
          ;;The compiler is careful to null out things that are no longer in use.
          objects))
      (is (= 0 @counter))
      (System/gc)
      (Thread/sleep 100)
      (is (= 0 @counter))))
  (testing "gc-only resources get cleaned up"
    (let [counter (atom 0)]
      (let [create-fn (fn []
                        (swap! counter inc)
                        (gc-resource/track-gc-only (Object.)
                                                   #(swap! counter dec)))
            objects (vec (repeatedly 10 #(create-fn)))]
        (is (= 10 @counter))
        nil)
      (System/gc)
      (Thread/sleep 100)
      (is (= 0 @counter)))))

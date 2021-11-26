(ns tech.v3.gc-resource-test
  (:require [tech.v3.resource :as resource]
            [clojure.test :refer [deftest is testing]]))


(deftest gc-resources
  (testing "System.gc cleans up the things"
    (let [counter (atom 0)]
      (let [create-fn (fn []
                        (swap! counter inc)
                        (resource/track (Object.) {:dispose-fn #(swap! counter dec)
                                                   :track-type :gc}))]
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
      (resource/stack-resource-context
        (let [create-fn (fn []
                          (swap! counter inc)
                          (resource/track (Object.) {:dispose-fn #(swap! counter dec)
                                                     :track-type [:gc :stack]}))
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
                        (resource/track (Object.) {:dispose-fn #(swap! counter dec)
                                                   :track-type :gc}))
            _objects (vec (repeatedly 10 #(create-fn)))]
        (is (= 10 @counter))
        nil)
      (System/gc)
      (Thread/sleep 100)
      (is (= 0 @counter)))))

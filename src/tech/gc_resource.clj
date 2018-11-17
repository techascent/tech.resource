(ns tech.gc-resource
  (:require [tech.resource :as resource])
  (:import [java.lang.ref ReferenceQueue]
           [java.lang Thread]
           [tech.resource GCReference]))

(set! *warn-on-reflection* true)


(def ^:dynamic *reference-queue* (ReferenceQueue.))


(defn watch-reference-queue
  [run-atom ^ReferenceQueue reference-queue]
  (try
    (println :tech.gc-resource "Reference thread starting")
    (loop [continue? @run-atom]
      (when continue?
        (let [next-ref (.remove reference-queue 100)]
          (when next-ref
            (if-not (satisfies? resource/PResource next-ref)
              (println :tech.gc-resource "ReferenceItem in queue is not releasable!!")
              (try
                (resource/release-resource next-ref)
                (catch Throwable e
                  (println :tech.gc-resource "Failed to release resource:" next-ref e)))))
          (recur @run-atom))))
    (catch Throwable e
      (println :tech.gc-resource "!!Error in reference queue!!:" e)))
  (println :tech.gc-resource "Reference queue exiting"))


(defn start-reference-thread
  []
  (let [run-atom (atom true)
        thread (Thread. #(watch-reference-queue  run-atom *reference-queue*))]
    (.start thread)
    {:thread thread
     :close-fn #(do
                  (reset! run-atom false)
                  (.join thread))}))


(def ^:dynamic *reference-thread* (start-reference-thread))


(extend-protocol resource/PResource
  GCReference
  (release-resource [^GCReference item]
    (.dispose item)))



(defn track-gc-only
  "Track this item using weak references.  Note that the dispose-fn must absolutely
  *not* reference the item else nothing will ever get released."
  [item dispose-fn]
  (GCReference. item ^ReferenceQueue *reference-queue* dispose-fn)
  item)


(defn track
  "Track an item via both the gc system *and* the stack based system.
Dispose will be first-one-wins."
  [item dispose-fn]
  (let [gc-ref (GCReference. item *reference-queue* dispose-fn)]
    (resource/track gc-ref)
    item))

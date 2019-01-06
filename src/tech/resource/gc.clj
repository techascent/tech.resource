(ns tech.resource.gc
  (:require [tech.resource.stack :as stack])
  (:import [java.lang.ref ReferenceQueue]
           [java.lang Thread]
           [tech.resource GCReference]
           [java.util IdentityHashMap Collections Set]
           [java.util.function Function]))


(set! *warn-on-reflection* true)


(def ^:dynamic *reference-queue* (ReferenceQueue.))
(def ^:dynamic *weak-reference-set* (-> (IdentityHashMap.)
                                        (Collections/newSetFromMap)))


(defn watch-reference-queue
  [run-atom ^ReferenceQueue reference-queue]
  (try
    (println :tech.resource.gc "Reference thread starting")
    (loop [continue? @run-atom]
      (when continue?
        (let [next-ref (.remove reference-queue 100)]
          (when next-ref
            (try
              (stack/do-release next-ref)
              ;;We can't let a bad thing kill the thread.  Do release already prints
              ;;diagnostic information so that is sufficient for now.
              (catch Throwable e nil)))
          (recur @run-atom))))
    (catch Throwable e
      (println :tech.resource.gc "!!Error in reference queue!!:" e)))
  (println :tech.resource.gc "Reference queue exiting"))


(defonce ^:dynamic *reference-thread* (atom nil))


(defn start-reference-thread
  []
  (when-not @*reference-thread*
    (let [run-atom (atom true)
          thread (Thread. #(watch-reference-queue  run-atom *reference-queue*))]
      ;;Do not stop the jvm from exiting...
      (.setDaemon thread true)
      (.setName thread "tech.resource.gc ref thread")
      (.start thread)
      (reset! *reference-thread*
              {:thread thread
               :close-fn #(do
                            (reset! run-atom false)
                            (.join thread))}))))


(defn stop-reference-thread
  []
  (when-let [close-fn (:close-fn @*reference-thread*)]
    (close-fn)
    (reset! *reference-thread* nil)))

;;We will
(start-reference-thread)


(defn track-gc-only
  "Track this item using weak references.  Note that the dispose-fn must absolutely
  *not* reference the item else nothing will ever get released."
  [item dispose-fn]
  (let [gc-ref (GCReference. item ^ReferenceQueue *reference-queue*
                             (proxy [Function] []
                                 (apply [this-ref]
                                        (locking *weak-reference-set*
                                          (.remove ^Set *weak-reference-set* this-ref))
                                   (dispose-fn))))]
    ;;We have to keep track of the gc-ref else *it* will get cleaned up and the dispose
    ;;fn will not get called!!
    (locking *weak-reference-set*
      (.add ^Set *weak-reference-set* gc-ref))
    item))


(defn track
  "Track an item via both the gc system *and* the stack based system.  Dispose will be
  first-one-wins.  Dispose-fn must not referent item else the circular dependency will
  stop the dispose-fn from being called."
  [item dispose-fn]
  (let [gc-ref (GCReference. item *reference-queue* (proxy [Function] []
                                                      (apply [this-ref]
                                                        (dispose-fn))))]
    (stack/track item gc-ref)
    item))

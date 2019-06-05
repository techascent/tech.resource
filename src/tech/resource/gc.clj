(ns tech.resource.gc
  "System for using both weak and soft references generically.  Weak references don't
  count for anything in the gc while soft references will keep their object alive as
  long as there is no gc pressure."
  (:require [tech.resource.stack :as stack]
            [clojure.tools.logging :as log])
  (:import [java.lang.ref ReferenceQueue]
           [java.lang Thread]
           [tech.resource GCReference GCSoftReference]
           [java.util IdentityHashMap Collections Set]
           [java.util.function Function]))


(set! *warn-on-reflection* true)


(def ^:dynamic *reference-queue* (ReferenceQueue.))
(def ^:dynamic *weak-reference-set* (-> (IdentityHashMap.)
                                        (Collections/newSetFromMap)))


(defn watch-reference-queue
  [run-atom ^ReferenceQueue reference-queue]
  (try
    (log/info "Reference thread starting")
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
      (log/errorf e "!!Error in reference queue!!")))
  (log/info "Reference queue exiting"))


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


(defn- create-reference
  [item dispose-fn track-reference? ptr-constructor]
  (let [retval (ptr-constructor item *reference-queue*
                                (proxy [Function] []
                                  (apply [this-ref]
                                    (when track-reference?
                                      (locking *weak-reference-set*
                                        (.remove ^Set *weak-reference-set* this-ref)))
                                    (dispose-fn))))]
    (when track-reference?
      (locking *weak-reference-set*
        (.add ^Set *weak-reference-set* retval)))
    retval))


(defn gc-reference
  "Create a weak reference to the item.  Return the actual reference.  You can get the
  item (assuming it hasn't been cleaned up) using .get method on the reference.  Note
  that dispose-fn must *not* reference item in any way else the item won't get cleaned
  up.

  IF track-reference is *true*, then the reference itself is added to the reference set.
  This keeps the reference itself from being gc'd.  This is not necessary if you know
  the reference will outlive the tracked object (or if you don't care)."
  ^GCReference [item dispose-fn & {:keys [track-reference?]}]
  (create-reference item dispose-fn track-reference?
                    #(GCReference. %1 %2 %3)))


(defn soft-reference
  "Create a soft reference to the item.  Return the actual reference.  You can get the
  item (assuming it hasn't been cleaned up) using .get method on the reference.  Note
  that dispose-fn must *not* reference item in any way else the item won't get cleaned
  up.

  If track-reference is *true*, then the reference itself is added to the reference set.
  This keeps the reference itself from being gc'd.  This is not necessary if you know
  the reference will outlive the tracked object (or if you don't care)."
  ^GCSoftReference [item dispose-fn & {:keys [track-reference?]}]
  (create-reference item dispose-fn track-reference?
                    #(GCSoftReference. %1 %2 %3)))


(defn track-gc-only
  "Track this item using weak references.  Note that the dispose-fn must absolutely
  *not* reference the item else nothing will ever get released."
  [item dispose-fn]
  ;;We have to keep track of the gc-ref else *it* will get cleaned up and the dispose fn
  ;;will not get called!!
  (-> (gc-reference item dispose-fn :track-reference? true)
      .get))


(defn track
  "Track an item via both the gc system *and* the stack based system.  Dispose will be
  first-one-wins.  Dispose-fn must not referent item else the circular dependency will
  stop the dispose-fn from being called."
  [item dispose-fn]
  (->> (gc-reference item dispose-fn :track-reference? true)
       (stack/track item))
  item)

(ns tech.v3.resource.stack
  "Implementation of stack based resource system.  Simple, predictable, deterministic,
  and applicable to most problems.  Resource contexts are sequences of resources that
  need to be, at some point, released."
  (:require [clojure.tools.logging :as log])
  (:import [java.lang Runnable]
           [java.io Closeable]
           [java.lang AutoCloseable]
           [clojure.lang IFn]
           [java.util ArrayList Collections]))

(set! *warn-on-reflection* true)


(defonce ^{:dynamic true
           :tag ArrayList} *resource-context* (ArrayList.))
(defonce ^:dynamic *bound-resource-context?* false)

(def ^:dynamic *resource-debug-double-free* nil)

(defn do-release [item]
  (when item
    (try
      (cond
        (instance? Runnable item)
        (.run ^Runnable item)
        (instance? Closeable item)
        (.close ^Closeable item)
        (instance? AutoCloseable item)
        (.close ^AutoCloseable item)
        (instance? IFn item)
        (item)
        :else
        (throw (Exception. (format "Item is not runnable, closeable, or an IFn: %s"
                                   (type item)))))
      (catch Throwable e
        (log/errorf e "Failed to release %s" item)))))


(defn track
  "Begin tracking this resource. Resource be released when current resource context
  ends.  If the item satisfies the PResource protocol, then it can be tracked
  itself.  Else the dispose function is tracked."
  ([item dispose-fn]
   (when (and *resource-debug-double-free*
              (some #(identical? item %) @*resource-context*))
     (throw (ex-info "Duplicate track detected; this will result in a double free"
                     {:item item})))
   (when-not *bound-resource-context?*
     (log/warn "Stack resource tracking used but no resource context bound.
This is probably a memory leak."))
   (locking *resource-context*
     (.add *resource-context* [item dispose-fn]))
   item)
  ([item]
   (track item item)))


(defn ignore-resources
  "Ignore these resources for which pred returns true and do not track them.
  They will not be released unless added again with track"
  [pred]
  (locking *resource-context*
    (let [^ArrayList retval (.clone *resource-context*)]
      (.removeIf *resource-context* (reify java.util.function.Predicate
                                      (test [this val] (boolean (pred (first val))))))
      (.removeIf retval (reify java.util.function.Predicate
                          (test [this val] (boolean (not (pred (first val)))))))
      retval)))


(defn ignore
  "Ignore specifically this resource."
  [item]
  (ignore-resources #(= item %))
  item)


(defn release
  "Release this resource and remove it from tracking.  Exceptions propagate to callers."
  [item]
  (when item
    (reduce (fn [acc entry]
              (do-release (second entry)))
            nil
            (ignore-resources #(= item %)))))



(defn release-resource-seq
  "Release a resource context returned from return-resource-context."
  [res-ctx & {:keys [pred]}]
  (Collections/reverse res-ctx)
  (if pred
    (reduce (fn [acc entry]
              (when (pred (nth entry 0))
                (do-release (nth entry 1))))
            nil res-ctx)
    (reduce (fn [acc entry]
              (do-release (nth entry 1)))
            nil res-ctx)))


(defn release-current-resources
  "Release all resources matching either a predicate or all resources currently tracked.
  Returns any exceptions that happened during release but continues to attempt to
  release anything else in the resource list."
  ([pred]
   (->> (if pred
          (ignore-resources pred)
          (locking *resource-context*
            (let [rv (.clone *resource-context*)]
              (.clear *resource-context*)
              rv)))
        (release-resource-seq)))
  ([] (release-current-resources nil)))


(defmacro with-resource-context
  "Begin a new resource context.  Any resources added while this context is open will be
  released when the context ends."
  [& body]
  `(with-bindings {#'*resource-context* (ArrayList.)
                   #'*bound-resource-context?* true}
     (try
       ~@body
       (finally
         (release-current-resources)))))

(defn ^:no-doc alist
  ^ArrayList [data]
  (if data 
    (ArrayList. ^java.util.Collection data)
    (ArrayList.)))


(defmacro with-bound-resource-seq
  "Run code and return both the return value and the (updated,appended) resources
  created.
  Returns:
  {:return-value retval
  :resource-seq resources}"
  [resource-seq & body]
  ;;It is important the resources sequences is a list.
  `(with-bindings {#'*resource-context* (alist ~resource-seq)
                   #'*bound-resource-context?* true}
     (try
       (let [retval# (do ~@body)]
         {:return-value retval#
          :resource-seq *resource-context*})
       (catch Throwable e#
         (release-current-resources)
         (throw e#)))))


(defmacro return-resource-seq
  "Run code and return both the return value and the resources the code created.
  Returns:
  {:return-value retval
  :resource-seq resources}"
  [& body]
  `(with-bound-resource-seq [] ~@body))

(ns tech.resource.stack
  "Implementation of stack based resource system.  Simple, predictable, deterministic,
  and applicable to most problems.  Resource contexts are sequences of resources that
  need to be, at some point, released."
  (:import [java.lang Runnable]))


(defprotocol PResource
  (release-resource [item]))


(defonce ^:dynamic *resource-context* (atom (list)))

(def ^:dynamic *resource-debug-double-free* nil)


(defn do-release [item]
  (when item
    (try
      (cond
        (satisfies? PResource item)
        (release-resource item)
        (instance? Runnable item)
        (.run ^Runnable item)
        :else
        (item))
     (catch Throwable e
       (println (format "Failed to release %s: %s" item e))
       (throw e)))))


(defn track
  "Begin tracking this resource. Resource be released when current resource context
  ends.  If the item satisfies the PResource protocol, then it can be tracked
  itself.  Else the dispose function is tracked."
  ([item dispose-fn]
   (when (and *resource-debug-double-free*
              (some #(identical? item %) @*resource-context*))
     (throw (ex-info "Duplicate track detected; this will result in a double free"
                     {:item item})))
   (swap! *resource-context* conj [item dispose-fn])
   item)
  ([item]
   (track item item)))


(defn ignore-resources
  "Ignore these resources for which pred returns true and do not track them.
  They will not be released unless added again with track"
  [pred]
  (loop [resources @*resource-context*]
    (let [retval (filter (comp pred first) resources)
          leftover (->> (remove (comp pred first) resources)
                        doall)]
      (if-not (compare-and-set! *resource-context* resources leftover)
        (recur @*resource-context*)
        retval))))


(defn ignore
  "Ignore specifically this resource."
  [item]
  (ignore-resources #(= item %))
  item)


(defn release
  "Release this resource and remove it from tracking.  Exceptions propagate to callers."
  [item]
  (when item
    (let [release-list (first (ignore-resources #(= item %)))]
      (when release-list
        (do-release (ffirst release-list))))))


(defn release-resource-seq
  "Release a resource context returned from return-resource-context."
  [res-ctx & {:keys [pred]
              :or {pred identity}}]
  (->> res-ctx
       (filter (comp pred first))
       ;;Avoid holding onto head.
       (map (fn [[item dispose-fn]]
              (try
                (do-release dispose-fn)
                nil
                (catch Throwable e e))))
       doall))


(defn release-current-resources
  "Release all resources matching either a predicate or all resources currently tracked.
  Returns any exceptions that happened during release but continues to attempt to
  release anything else in the resource list."
  ([pred]
   (let [leftover (ignore-resources pred)]
     (release-resource-seq leftover)))
  ([]
   (release-current-resources (constantly true))))


(defmacro with-resource-context
  "Begin a new resource context.  Any resources added while this context is open will be
  released when the context ends."
  [& body]
  `(with-bindings {#'*resource-context* (atom (list))}
     (try
       ~@body
       (finally
         (release-current-resources)))))


(defmacro with-bound-resource-seq
  "Run code and return both the return value and the (updated,appended) resources
  created.
  Returns:
  {:return-value retval
  :resource-seq resources}"
  [resource-seq & body]
  ;;It is important the resources sequences is a list.
  `(with-bindings {#'*resource-context* (atom (seq ~resource-seq))}
     (try
       (let [retval# (do ~@body)]
         {:return-value retval#
          :resource-seq @*resource-context*})
       (catch Throwable e#
         (release-current-resources)
         (throw e#)))))


(defmacro return-resource-seq
  "Run code and return both the return value and the resources the code created.
  Returns:
  {:return-value retval
  :resource-seq resources}"
  [& body]
  `(with-bound-resource-seq (list) ~@body))

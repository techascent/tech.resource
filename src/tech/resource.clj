(ns tech.resource)


(defprotocol PResource
  (release-resource [item]))


(defn- do-release [item]
  (when item
    (try
      (release-resource item)
      (catch Throwable e
        (println (format "Failed to release %s: %s" item e))
        (throw e)))))


(defonce ^:dynamic *resource-context* (atom (list)))

(def ^:dynamic *resource-debug-double-free* nil)

(defn track
  "Begin tracking this resource. Resource be released when current resource context ends"
  [item]
  (when (and *resource-debug-double-free*
             (some #(identical? item %) @*resource-context*))
    (throw (ex-info "Duplicate track detected; this will result in a double free"
                    {:item item})))
  (swap! *resource-context* conj item)
  item)


(defn ignore-resources
  "Ignore these resources for which pred returns true and do not track them.
  They will not be released unless added again with track"
  [pred]
  (swap! *resource-context* #(doall (remove pred %))))


(defn ignore
  "Ignore specifically this resource."
  [item]
  (ignore-resources #(= item %))
  item)


(defn release
  "Release this resource and remove it from tracking.  Exceptions propagate to callers."
  [item]
  (when item
    (ignore item)
    (do-release item)))


(defn release-resource-seq
  "Release a resource context returned from return-resource-context."
  [res-ctx & {:keys [pred]
              :or {pred identity}}]
  (->> res-ctx
       (filter pred)
       ;;Avoid holding onto head.
       (map (fn [item]
              (try
                (do-release item)
                nil
                (catch Throwable e e))))
       doall))


(defn release-current-resources
  "Release all resources matching either a predicate or all resources currently tracked.
Returns any exceptions that happened during release but continues to attempt to release
anything else in the resource list."
  ([pred]
   (loop [cur-resources @*resource-context*]
     (if-not (compare-and-set! *resource-context* cur-resources
                               ;;Laziness is not a friend here.
                               (->> (remove pred cur-resources)
                                    doall))
       (recur @*resource-context*)
       (release-resource-seq cur-resources))))
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
  "Run code and return both the return value and the (updated,appended) resources created.
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


(defrecord Releaser [release-fn!]
  PResource
  (release-resource [item] (release-fn!)))


(defn make-resource
  "Make a releaser out of an arbitrary closure"
  [release-fn!]
  (-> (->Releaser release-fn!)
      track))


(defn safe-create
  "Create a resource and assign it to an atom.  Allows threadsafe implementation of
  singelton type resources.  Implementations need to take care that in the case of
  conflict their resource may be destroyed when the atom has not been set yet so their
  release-resource implementation needs to use compare-and-set! instead of reset! in
  order to clear the atom"
  [resource-atom create-fn]
  (loop [retval @resource-atom]
    (if-not retval
      (let [retval (create-fn)]
        (if-not (compare-and-set! resource-atom nil retval)
          (do
            (release-resource retval)
            (recur @resource-atom))
          (track retval)))
      retval)))

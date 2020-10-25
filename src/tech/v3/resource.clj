  (ns tech.v3.resource
  "System of calling functions with side effects when an object goes out of scope.  Scoping
  can be defined as gc-based scoping or stack-based scoping or a combination of the two of them
  in which case the behavior becomes 'release-no-later-than'.

  For GC resources, users must not reference the tracked object in the dispose function
  else the circular dependency will keep the object in the gc's live set"
  (:require [tech.v3.resource.stack :as stack]
            [tech.v3.resource.gc :as gc])
  (:import [java.io Closeable]
           [java.lang AutoCloseable]))


(defn- normalize-track-type
  [track-type]
  (let [track-type (or track-type :gc)]
    (if (keyword? track-type)
      #{track-type}
      (set track-type))))


(defmulti ^:private track-impl
  "Internal implementation to differentiate between different forms of tracking
  resources."
  (fn [_ _ track-type]
    track-type))


(defmethod track-impl #{:stack}
  [item dispose-fn _]
  (stack/track item dispose-fn))


(defmethod track-impl #{:gc}
  [item dispose-fn _]
  (gc/track-gc-only item dispose-fn))


(defmethod track-impl #{:gc :stack}
  [item dispose-fn _]
  (gc/track item dispose-fn))


(defn track
  "Track a resource.  If the item inherents from PResource or is a clojure fn, or a
  Runnable object then it can be cleaned up by the stack system with no further dispose
  function.  Objects tracked by the gc need to have a dispose fn that does *not*
  reference the tracked object.

  Using stack-based resource tracking when there is no stack resource context open
  will generate a warning every time as it guarantees a memory leak.

  Track types can be :gc, :stack, or [:gc :stack] with :gc being the default tracking type."
  ([item {:keys [track-type dispose-fn]}]
   (let [track-type (normalize-track-type track-type)]
     (when (and (contains? track-type :gc)
                (not dispose-fn))
       (throw (ex-info "gc track types must have a dispose function that does *not*
reference item."
                       {:item item
                        :track-type track-type})))
     (let [dispose-fn (or dispose-fn item)]
       (when-not (or (instance? Runnable dispose-fn)
                     (instance? Closeable dispose-fn)
                     (instance? AutoCloseable dispose-fn)
                     (fn? dispose-fn))
         (throw (ex-info "The dispose method must implement be Runnable, Closeable, or a
clojure function."
                         {:dispose-fn dispose-fn})))
       (track-impl item dispose-fn track-type))))
  ([item]
   (track item nil)))


(defmacro stack-resource-context
  "Stack resource context.  When this context unwinds, stack resource declared within
  will be released."
  [& body]
  `(stack/with-resource-context ~@body))


(defn chain-resources
  "Chain an older resource to a newer (derived) one such that the older
  resource cannot get cleaned up before the newer resource."
  [new-resource old-resource]
  (gc/track-gc-only new-resource (constantly old-resource)))

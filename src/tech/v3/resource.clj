(ns tech.v3.resource
  "System of calling functions with side effects when an object goes out of scope.  Scoping
  can be defined as gc-based scoping or stack-based scoping or a combination of the two of them
  in which case the behavior becomes 'release-no-later-than'.

  For GC resources, users must not reference the tracked object in the dispose function
  else the circular dependency will keep the object in the gc's live set"
  (:require [tech.v3.resource.stack :as stack]
            [tech.v3.resource.gc :as gc])
  (:import [java.io Closeable]
           [clojure.lang IDeref]
           [java.lang AutoCloseable]
           [java.util Map]
           [java.util.concurrent.atomic AtomicReference]))



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

(defn in-stack-resource-context?
  "Returns true if the current running code is inside a stack
  resource context."
  []
  stack/*bound-resource-context?*)


(defn ^:no-doc normalize-track-type
  [track-type]
  (let [track-type (or track-type :auto)]
    (cond
      (= track-type :auto)
      (if (in-stack-resource-context?)
        #{:stack}
        #{:gc})
      (keyword? track-type)
      #{track-type}
      :else
      (set track-type))))


(defn track
  "Track a resource.  If the item inherents from PResource or is a clojure fn, or a
  Runnable object then it can be cleaned up by the stack system with no further dispose
  function.  Objects tracked by the gc need to have a dispose fn that does *not*
  reference the tracked object.

  Using stack-based resource tracking when there is no stack resource context open
  will generate a warning every time as it guarantees a memory leak.



  Options:

  * `:track-type` - Track types can be :gc, :stack, [:gc :stack] or :auto with :auto being the default
     tracking type.

     * `:gc` - Cleanup will be called just after the original object is garbage collected.
     * `:stack` - Get cleaned up when the stack resource context is cleaned up.  This means a stack
        returns context must be open.
     * `:auto`: Will use stack if a stack is open and gc if one is not."
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


(defmacro releasing!
  "Resources allocated within this stack frame will be released when
  this code returns. Synonym for [[stack-resource-context]]."
  [& body]
  `(stack/with-resource-context ~@body))


(defn chain-resources
  "Chain an older resource to a newer (derived) one such that the older
  resource cannot go out of gc scope before the newer resource has.  This
  allows you to create 'sub' objects and ensure the parent object cannot get
  cleaned up before 'sub' objects.


  This is a very costly way of doing this and if misused it can lead to false
  OOM situations.  The reason is that the link to the parent object is only broken
  *after* the GC run so it takes as many gc runs as the depth of the object graph so
  your code can easily create object graphs faster than it will cause gc runs.

  Because of this it is much better to just have a member variable or metadata
  that points back to the parent."
  [new-resource old-resource]
  (gc/track-gc-only new-resource (constantly old-resource)))


(defprotocol Bindable
  (execute-with-bound! [this ifn]
    "Bind this object to be the current resource context and execute function."))


(extend-protocol Bindable
  nil
  (execute-with-bound! [this ifn] (ifn))
  Map
  (execute-with-bound! [this ifn]
    (if-let [ctx (this ::resource-context)]
      (execute-with-bound! ctx ifn)
      (throw (RuntimeException. (str "Map does not appear to have a resource context:\n"
                                     (pr-str this)))))))


(defn ^:no-doc make-closeable!
  [rv res-seq]
  (let [res (AtomicReference. (or res-seq (list)))]
    (reify
      java.lang.AutoCloseable
      (close [this]
        (locking this
          (when-let [cur-res (.getAndSet res nil)]
            (stack/release-resource-seq cur-res))))
      Bindable
      (execute-with-bound! [this ifn]
        (locking this
          (let [retval (volatile! nil)]
            (.getAndUpdate res (reify java.util.function.UnaryOperator
                                 (apply [this cur-res]
                                   (when-not cur-res
                                     (throw (RuntimeException. "Resources have been closed.")))
                                   (let [{:keys [return-value resource-seq]}
                                         (stack/with-bound-resource-seq cur-res (ifn))]
                                     (vreset! retval return-value)
                                     resource-seq))))
            @retval)))
      IDeref
      (deref [this] rv))))


(defmacro closeable!
  "Run code in a context and then return that context as a java.util.AutoCloseable.

  Run more code in the current stack context by using [[with-bound!]] - thus potentially
  binding more objects to the current resource context.

  All objects released tracked with this context bound with track-type :stack or :auto will
  be released when the returned object is `close`d.  The returned object is derefable and
  when derefed returns the return value of the code ran during its creation.

```clojure
user> (def res (resource/closeable! (let [v (atom 1)]
                                      (resource/track v {:dispose-fn #(swap! v dec)}))))
#'user/res
user> @res
#<Atom@34bc01ac: 1>
user> (resource/with-bound! res (let [v (atom 2)] (resource/track v {:dispose-fn #(swap! v inc)})))

#<Atom@2d0f007c: 2>
user> (.close res)
nil
user> *2
#<Atom@2d0f007c: 3>
user> res
#<resource$make_closeable_BANG_$reify__8490@48ce3240: #<Atom@34bc01ac: 0>>
user> (.close res)
nil
user> (resource/with-bound! res (let [v (atom 2)] (resource/track v {:dispose-fn #(swap! v inc)})))

Execution error at tech.v3.resource$make_closeable_BANG_$reify$reify__8491/apply (form-init143758951282771418.clj:164).
Resources have been closed.
```"
  ^java.lang.AutoCloseable [& body]
  `(let [{return-value# :return-value
          resource-seq# :resource-seq} (stack/return-resource-seq ~@body)]
     (make-closeable! return-value# resource-seq#)))


(defmacro with-bound!
  "Bind a closeable object as the current resource context.
  Returns the return value of body.

  See documentation for [[closeable!]]."
  [c & body]
  `(execute-with-bound! ~c (fn [] ~@body)))

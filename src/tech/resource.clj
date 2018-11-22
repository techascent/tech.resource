(ns tech.resource
  (:require [tech.resource.stack :as stack]
            [tech.resource.gc :as gc]))


(defn- normalize-track-type
  [track-type]
  (let [track-type (or track-type :stack)]
    (if (keyword? track-type)
      #{track-type}
      (set track-type))))


(defmulti track-impl
  (fn [item dispose-fn track-type]
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
  [item & [dispose-fn track-type]]
  (let [track-type (normalize-track-type track-type)]
    (when (and (contains? track-type :gc)
               (not dispose-fn))
      (throw (ex-info "gc track types must have a dispose function
that does *not* reference item."
                      {:item item
                       :track-type track-type})))
    (let [dispose-fn (or dispose-fn item)]
      (track-impl item dispose-fn track-type))))


(defmacro stack-resource-context
  [& body]
  `(stack/with-resource-context ~@body))

# resource

Generic threadsafe and exception-safe resource management.

```clojure
[thinktopic/think.resource "1.2.0"]


(require '[think.resource.core :as resource])


(extend-protocol resource/PResource
  RandomAccessFile
  (release-resource [^RandomAccessFile item] (.close item)))

;;create the file
(defn new-random-file
  [fname]
  (-> (RandomAccessFile. fname)
    resource/track))


;;Use it

(resource/with-resource-context
  (let [f (new-random-file fname)]
    ...
    ))

;;Similar to with-open, this will close the file regardless
;;of what happens.  The advantage is this can map to anything
;;you can implement the resource protocol with meaning network sockets,
;;JNI pointers, GPU contexts, etc.
```

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.  Complements of ThinkTopic, LLC.

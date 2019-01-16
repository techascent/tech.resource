# tech.resource

[![Clojars Project](https://img.shields.io/clojars/v/techascent/tech.resource.svg)](https://clojars.org/techascent/tech.resource)


Generic thread-safe and exception-safe non-gc or 'off heap' resource management.


There is some more information on our [blog](http://techascent.com/blog/generalized-resource-management.html).



```clojure
;;create the file
(defn new-random-file
  [fname]
  (resource/track (RandomAccessFile. fname) #(.close item)))


;;Use it
(resource/stack-resource-context
  (let [f (new-random-file fname)]
    ...
    ))

;;Similar to with-open, this will close the file regardless
;;of what happens.  The advantage is this can map to anything
;;you can implement the resource protocol with meaning network sockets,
;;JNI pointers, GPU contexts, etc.  It is also possible to have the
;;gc track something if the dispose functionality does not reference
;;the item itself.

(let [f (resource/track (double-array [1 2 3]) #(println "disposed") :gc)]
  ...)
;;Disposed will print when the gc determines the double array is no longer
;;reachable
```


There are now explicit methods to create either a weak or soft reference.
For the differences, see 
[here](https://stackoverflow.com/questions/299659/whats-the-difference-between-softreference-and-weakreference-in-java).

* [gc reference documentation and implementation](https://github.com/techascent/tech.resource/blob/master/src/tech/resource/gc.clj).
* [stack documentation and implementation](https://github.com/techascent/tech.resource/blob/master/src/tech/resource/stack.clj).


### Usage


Checkout the [stack](test/tech/resource_test.clj) and 
[gc](test/tech/gc_resource_test.clj) tests.



Or take a giant leap and check out [tvm-clj](https://github.com/techascent/tvm-clj).




Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.  Complements of Tech Ascent, LLC.

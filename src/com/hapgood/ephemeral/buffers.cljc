(ns com.hapgood.ephemeral.buffers
  (:require [clojure.core.async.impl.protocols :as impl]))

;; Like a PromiseBuffer, but can be reset to unrealized by adding the sentinel.
(deftype ResettablePromiseBuffer [^:unsynchronized-mutable val sentinel]
  impl/UnblockingBuffer
  impl/Buffer
  (full? [_] false)
  (remove! [_] val)
  (add!* [this item] (set! val item) this)
  (close-buf! [_] (set! val nil))
  #?@(:clj (clojure.lang.Counted
            (count [_] (if (= sentinel val) 0 1)))
      :cljs (ICounted
             (-count [_] (if (= sentinel val) 0 1)))))

(defn resettable-promise-buffer [v] (ResettablePromiseBuffer. v v))

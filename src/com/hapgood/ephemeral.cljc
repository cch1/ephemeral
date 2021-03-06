(ns com.hapgood.ephemeral
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- delta-t [t0 t1] (- (inst-ms t1) (inst-ms t0)))

;; A channel-like type that coordinates the supply of fresh ephemeral values.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Ephemeral [out-ref in m]
  impl/ReadPort
  (take! [this fn-handler] (impl/take! @out-ref fn-handler))
  impl/WritePort
  (put! [port val fn1-handler] (impl/put! in val fn1-handler))
  impl/Channel
  (close! [this] (impl/close! in) (impl/close! @out-ref))
  (closed? [this] (impl/closed? @out-ref))
  #?@(:clj (clojure.lang.IMeta
            (meta [this] @m)
            clojure.lang.IObj
            (withMeta [this m'] (reset! m m')))
      :cljs (IMeta
             (-meta [this] @m)
             IWithMeta
             (-with-meta [this m'] (reset! m m'))))
  ;; Inspired by https://clojure.atlassian.net/browse/ASYNC-102
  #?@(:clj (clojure.lang.IDeref ; This interface is semantically inappropriate for ClojureScript, right?
            (deref [this]
                   (let [p (promise)]
                     (async/take! this (fn [x] (deliver p x)))
                     (deref p)))
            clojure.lang.IBlockingDeref
            (deref [this timeout fallback]
                   (let [t (async/timeout timeout)
                         p (promise)
                         [val port] (async/alts!! [t this])]
                     (if (= this port) val fallback)))))
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Ephemeral " (pr-str v) ">")
                     "#<Ephemeral >")))

(defmulti schedule
  "Schedule the optional expiration and refreshing of the ephemeral value.  Implementations
  should return a tuple of [`expires-at` `refresh-after`] where `expires-at` is an optional instant
  when the value should expire and `refresh-after` is an integer duration before an attempt should
  be made to refresh the ephemeral value.  If `expires-at` is nil, the value will considered fresh
  and be supplied to consumers indefinitely.  If `refresh-after` is negative then the value is
  already stale and will never be returned to consumers.  The schedule multimethod is called with
  the `t` value returned by the acquire function and the measured `latency` of the acquisition."
  (fn [t latency] (type t)))
(defmethod schedule #?(:clj java.lang.Long :cljs js/Number) [interval latency]
  (let [refresh-after (max 0 (- interval latency))]
    [nil refresh-after]))
(defmethod schedule :default [inst latency]
  {:pre [(inst? inst)]}
  (let [lifespan (delta-t (now) inst)
        refresh-after (- lifespan latency)
        fresh? (not (neg? refresh-after))]
    [(when fresh? inst) refresh-after]))

(defn create
  "Create an ephemeral that is be supplied by the provided `acquire` function.  Use the optional
  `backoffs` sequence to control backoff delays when the `acquire` function reports failure.  The
  default is exponential backoff capped at 30s.

  The `acquire` function is passed the channel-like ephemeral onto which it must place a tuple
  of [`value` `t`] where `t` informs the schedule for refreshing and optionally expiring the
  ephemeral `value` (determined by the `schedule` multimethod).  The `acquire` function can
  synchronously report a retryable failure by throwing an exception.  The `acquire` function
  can asynchronously report a retryable failure by placing a value that does not satisfy
  `sequential?` on the ephemeral channel.

  If the ephemeral channel is closed all resources are freed and no further updates to the
  ephemeral will be attempted."
  ([acquire] (let [capped-exponential-backoff (concat (take 15 (iterate (partial * 2) 1)) (repeat 30000))]
               (create acquire capped-exponential-backoff)))
  ([acquire backoffs-all]
   {:pre [(fn? acquire) (seqable? backoffs-all)]}
   (let [out-ref (atom (async/promise-chan))
         in (async/chan 1)
         eph (->Ephemeral out-ref in (atom {::version 0}))]
     ;; coordinate the out-ref promise-channel from value arriving on the in channel
     (async/go-loop [expiry nil alarm (async/timeout 0) called-at nil backoffs backoffs-all]
       (let [[event port] (async/alts! (filter identity [alarm in expiry]))
             now (now)]
         (when-let [[e a c bs] (condp = port
                                 expiry (do (reset! out-ref (async/promise-chan))
                                            [nil alarm called-at backoffs])
                                 alarm (try (acquire eph)
                                            [expiry nil now backoffs]
                                            (catch #?(:clj java.lang.Exception :cljs js/Error) _
                                              [expiry (async/timeout (first backoffs)) now (rest backoffs)]))
                                 in (when event
                                      (if (sequential? event) ; did the acquire fn provide a value tuple?
                                        (let [[v expires-at] event
                                              latency (delta-t called-at now)
                                              [expire-at refresh-after] (schedule expires-at latency)
                                              fresh? (not (neg? refresh-after))
                                              expire-after (when (and fresh? expire-at) (delta-t now expire-at))]
                                          (vary-meta eph #(-> %
                                                              (merge {::acquired-at now ::expires-at expire-at ::latency latency ::anomaly nil})
                                                              (update ::version inc)))
                                          (when fresh?
                                            (let [[pc pc'] (reset-vals! out-ref (async/promise-chan))]
                                              (async/offer! pc v) ; release any previously blocked takes
                                              (async/offer! pc' v)))
                                          [(when expire-after (async/timeout expire-after)) (async/timeout refresh-after) nil backoffs-all])
                                        (let [backoff (first backoffs)]
                                          (vary-meta eph assoc ::anomaly {::reported-at now ::event event ::backoff backoff})
                                          [expiry (async/timeout backoff) nil (rest backoffs)]))))]
           (recur e a c bs))))
     eph)))

#?(:clj
   (do (defmethod clojure.core/print-method Ephemeral
         [ephemeral ^java.io.Writer writer]
         (.write writer (.toString ephemeral)))
       (defmethod clojure.pprint/simple-dispatch Ephemeral
         [ephemeral]
         (print-method ephemeral *out*)))
   :cljs
   (extend-protocol IPrintWithWriter
     Ephemeral
     (-pr-writer [this writer opts]
       (-write writer (.toString this)))))

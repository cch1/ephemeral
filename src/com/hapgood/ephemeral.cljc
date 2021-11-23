(ns com.hapgood.ephemeral
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- delta-t [t0 t1] (- (inst-ms t1) (inst-ms t0)))

;; A channel-like type that coordinates the supply of fresh ephemeral values.
;; Unlimited takes of a fresh value may be performed.  When the value expires, the ephemeral channel will block on takes.
;; The ephemeral channel cycles between these two states based on the timely supply of fresh values.
;; NB: Avoid TOCTOU (time-of-check-time-of-use) race conditions by never holding a captured value -that
;; defeats the ephemeral pattern.  Due to network and processing delays TOCTOU is a potential problem even
;; when not holding captured values.  Consider adding some margin to the expiry of ephemeral values (by
;; expiring them sooner) and/or conservatively refreshing them.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Ephemeral [current source m]
  impl/ReadPort
  (take! [this fn-handler] (impl/take! @current fn-handler))
  impl/WritePort
  (put! [port val fn1-handler] (impl/put! source val fn1-handler))
  impl/Channel
  (close! [this] (impl/close! source))
  (closed? [this] (impl/closed? @current))
  #?@(:clj (clojure.lang.IMeta
            (meta [this] @m)
            clojure.lang.IObj
            (withMeta [this m'] (reset! m m')))
      :cljs (IMeta
             (-meta [this] @m)
             IWithMeta
             (-with-meta [this m'] (reset! m m')) ))
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Ephemeral " (pr-str v) ">")
                     "#<Ephemeral >")))

(defn create
  "Create an ephemeral that is be supplied by the provided `acquire` function.  Use the optional
  `backoffs` sequence to control backoff delays when the `acquire` function reports failure.  The
  default is exponential backoff capped at 30s.

  The `acquire` function is passed the channel-like ephemeral onto which it must place a tuple
  of [`value` `expires-at`] where `expires-at` is the inst at which the ephemeral `value` will
  expire.  The `acquire` function can synchronously report a retryable failure by throwing an
  exception.  The `acquire` function can asynchronously report a retryable failure by placing a
  value that does not satisfy `sequential?` on the ephemeral channel.

  If the ephemeral channel is closed all resources are freed and no further updates to the
  ephemeral will be attempted."
  ([acquire] (let [capped-exponential-backoff (concat (take 15 (iterate (partial * 2) 1)) (repeat 30000))]
               (create acquire capped-exponential-backoff)))
  ([acquire backoffs-all]
   {:pre [(fn? acquire) (seqable? backoffs-all)]}
   (let [current (atom (async/promise-chan))
         source (async/chan 1)
         eph (->Ephemeral current source (atom {::version 0}))]
     ;; coordinate the current promise-channel from value arriving on the source channel
     (async/go-loop [expiry nil alarm (async/timeout 0) called-at nil backoffs backoffs-all]
       (let [[event port] (async/alts! (filter identity [alarm source expiry]))
             now (now)]
         (if-let [[e a c b] (condp = port
                              expiry (do (reset! current (async/promise-chan))
                                         [nil alarm called-at backoffs])
                              alarm (try (acquire eph)
                                         [expiry nil now backoffs]
                                         (catch #?(:clj java.lang.Exception :cljs js/Error) _
                                           [expiry (async/timeout (first backoffs)) now (rest backoffs)]))
                              source (when event
                                       (if (sequential? event) ; did the acquire fn provide a value tuple?
                                         (let [[v expires-at] event
                                               latency (delta-t called-at now)
                                               lifespan (delta-t now expires-at)
                                               pchan (async/promise-chan)
                                               [pc pc'] (swap-vals! current (constantly pchan))]
                                           (vary-meta eph (fn [m] (-> m
                                                                      (merge {::acquired-at now ::expires-at expires-at ::latency latency})
                                                                      (assoc ::anomaly nil)
                                                                      (update ::version inc))))
                                           (async/offer! pc v) ; release any previously blocked takes
                                           (async/offer! pc' v)
                                           [(async/timeout lifespan) (async/timeout (- lifespan latency)) nil backoffs-all])
                                         (let [backoff (first backoffs)]
                                           (vary-meta eph assoc ::anomaly {::reported-at now ::event event ::backoff backoff})
                                           [expiry (async/timeout backoff) nil (rest backoffs)]))))]
           (recur e a c b)
           (async/close! @current))))
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

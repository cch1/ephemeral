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
(deftype Ephemeral [current source]
  impl/ReadPort
  (take! [this fn-handler] (impl/take! @current fn-handler))
  impl/WritePort
  (put! [port val fn1-handler] (impl/put! source val fn1-handler))
  impl/Channel
  (close! [this] (impl/close! source))
  (closed? [this] (impl/closed? source))
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Ephemeral " (pr-str v) ">")
                     "#<Ephemeral >")))

(defn create
  "Create an ephemeral that can be supplied by the provided `acquire` function

  The `acquire` function is passed a channel onto which it must place a tuple
  of [`value` `expires-at`] where `expires-at` is the inst at which the ephemeral
  `value` will expire."
  [acquire]
  {:pre [(fn? acquire)]}
  (let [current (atom (async/promise-chan))
        source (async/chan 1)
        eph (->Ephemeral current source)]
    ;; coordinate the current promise-channel from [value, expires-at] tuples arriving on the source channel
    (async/go-loop [expiry nil alarm (async/timeout 0) called-at nil backoff 1]
      (let [[event port] (async/alts! (filter identity [alarm source expiry]))
            now (now)]
        (if-let [[e a c b] (condp = port
                             expiry (do (reset! current (async/promise-chan))
                                        (tap> {::event {::type ::expiry}})
                                        [nil alarm called-at backoff])
                             alarm (do
                                     (tap> {::event {::type ::alarm ::called-at now}})
                                     (try (acquire eph)
                                          [expiry nil now 1]
                                          (catch #?(:clj java.lang.Exception :cljs js/Error) _
                                            (let [backoff (* 2 backoff)]
                                              (tap> {::event {::type ::call-failed ::backoff backoff}})
                                              [expiry (async/timeout backoff) now backoff]))))
                             source (when-let [[v expires-at] event]
                                      (if (seq event) ; Was a fresh value acquired?
                                        (let [latency (delta-t called-at now)
                                              lifespan (delta-t now expires-at)
                                              [pc pc'] (swap-vals! current (constantly (async/promise-chan)))]
                                          (tap> {::event {::type ::source ::expires-at expires-at ::latency latency}})
                                          (async/offer! pc v) ; release any previously blocked takes
                                          (async/offer! pc' v)
                                          [(async/timeout lifespan) (async/timeout (- lifespan latency)) nil 1])
                                        (let [backoff (* 2 backoff)]
                                          (tap> {::event {::type ::source-failed}})
                                          [expiry (async/timeout backoff) nil backoff]))))]
          (recur e a c b)
          (do (swap! current (fn [pc] (async/close! pc) pc))
              (tap> {::event {::type ::shutdown}})))))
    eph))

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

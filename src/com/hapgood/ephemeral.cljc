(ns com.hapgood.ephemeral
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]))

(defprotocol IEphemeral
  (capture [ref] [ref fallback]  "Return the value of `ref` if available, otherwise return `fallback`")
  (available? [ref] "Return true if `ref` is available, otherwise false"))

(defprotocol IPerishable
  (refresh! [ref v expires-at] "Refresh the container with the value `v` which will expire at the inst `expires-at`.")
  (expiry [ref] "Return the inst of expiration for the current value of `ref` or nil if it is already expired")
  (stop [ref] "Stop the pre-emptive update process for this `ref`."))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- delta-t [t0 t1] (- (inst-ms t1) (inst-ms t0)))

;; A reference type whose value may only be available transiently.  The referenced value can be captured
;; when available but it may asynchronously become unavailable (and then available, and then ...).
;; NB: Avoid TOCTOU (time-of-check-time-of-use) race conditions by never holding a captured value -that
;; defeats the ephemeral pattern.  Due to network and processing delays TOCTOU is a potential problem even
;; when not holding captured values.  Consider adding some margin to the expiry of ephemeral values (by
;; expiring them sooner) and/or conservatively refreshing them.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Ephemeral [current source]
  IEphemeral
  ;; This is the primary synchronous read interface.  Other sync reads should go through this method.
  (capture [this fallback] (or (-> current deref first async/poll!) fallback))
  (capture [this] (let [v (capture this ::unavailable)]
                    (if (= ::unavailable v)
                      (let [m "The ephemeral value is not available."]
                        (throw #?(:clj (java.lang.IllegalStateException. m) :cljs (js/Error. m))))
                      v)))
  (available? [this] (not= ::unavailable (capture this ::unavailable)))
  IPerishable
  (refresh! [this v expires-at] (async/put! source [v expires-at]))
  (expiry [this] (-> current deref second))
  (stop [this] (async/close! source))
  impl/ReadPort
  ;; This is the primary asynchronous read interface.
  (take! [this fn-handler] (impl/take! (-> current deref first) fn-handler))
  #?@(:clj
      ;; The clojure.lang.IDeref 'protocol' is implemented since that is common practice for value containers.
      (clojure.lang.IDeref (deref [this] (-> current deref first async/<!!)))
      ;; The IDeref protocol is a semantic mismatch in ClojureScript since it assumes synchronous behavior.  We can't
      ;; offer synchronicity without some escape hatch (see `capture` above) so we don't implement IDeref in ClojureScript.
      )
  Object
  (toString [this] (let [v (capture this ::unavailable)]
                     (if (= v ::unavailable)
                       "#<Ephemeral >"
                       (str "#<Ephemeral " (pr-str v) ">")))))

(defn create
  "Create an ephemeral that can be supplied by the provided `acquire` function

  The `acquire` function is passed a channel onto which it must place a tuple
  of [`value` `expires-at`] where `expires-at` is the inst at which the ephemeral
  `value` will expire."
  [acquire]
  {:pre [(fn? acquire)]}
  (let [current (atom [(async/promise-chan) nil])
        source (async/chan 1)]
    ;; coordinate the current [promise-channel, expires-at] tuple from [value, expires-at] tuples arriving on the source channel
    (async/go-loop [expiry nil alarm (async/timeout 0) called-at nil backoff 1]
      (let [[event port] (async/alts! (filter identity [alarm source expiry]))
            now (now)]
        (if-let [[e a c b] (condp = port
                             expiry (do (swap! current assoc-in [0] (async/promise-chan))
                                        (tap> {::event {::type ::expiry}})
                                        [nil alarm called-at backoff])
                             alarm (do
                                     (tap> {::event {::type ::alarm ::called-at now}})
                                     (try (acquire source)
                                          [expiry nil now 1]
                                          (catch #?(:clj java.lang.Exception :cljs js/Error) _
                                            (let [backoff (* 2 backoff)]
                                              (tap> {::event {::type ::call-failed ::backoff backoff}})
                                              [expiry (async/timeout backoff) now backoff]))))
                             source (when-let [[v expires-at] event]
                                      (if (seq event) ; Was a fresh value acquired?
                                        (let [latency (delta-t called-at now)
                                              lifespan (delta-t now expires-at)
                                              [[pc _] [pc' _]] (swap-vals! current (constantly [(async/promise-chan) expires-at]))]
                                          (tap> {::event {::type ::source ::expires-at expires-at ::latency latency}})
                                          (async/offer! pc v) ; release any previously blocked takes
                                          (async/offer! pc' v)
                                          [(async/timeout lifespan) (async/timeout (- lifespan latency)) nil 1])
                                        (let [backoff (* 2 backoff)]
                                          (tap> {::event {::type ::source-failed}})
                                          [expiry (async/timeout backoff) nil backoff]))))]
          (recur e a c b)
          (do (swap! current (fn [[pc _]] (async/close! pc) [pc nil]))
              (tap> {::event {::type ::shutdown}})))))
    (->Ephemeral current source)))

(defmacro stopping
  "binding-pair => [name init]

  Evaluates body in a try expression with name bound to the value
  of the init, and a finally clause that calls (stop name)."
  [binding-pair & body]
  `(let ~binding-pair
     (try
       (do ~@body)
       (finally
         (stop ~(binding-pair 0))))))

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

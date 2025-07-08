(ns com.hapgood.ephemeral
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [com.hapgood.ephemeral.buffers :refer [resettable-promise-buffer]]))

(defprotocol Stateful
  (inspect [this] "Return the state of this."))

(defn- now [] #?(:clj (System/currentTimeMillis) :cljs (inst-ms (js/Date.))))

(defn- insist
  "Resiliently evaluate the function `f`.  Returns a channel whose only value
  is the return of `f`.  If `f` returns nil or throws an exception, it is
  retried after a delay (in ms) taken from `backoffs`."
  [f backoffs >metrics]
  {:pre [(instance? clojure.lang.IFn f) (seqable? backoffs)]}
  (swap! >metrics assoc :acquire {:started-at (now) :invoked 0 :pending 0 :failed 0 :retries 0})
  (async/go-loop [ret nil t (async/timeout 0) backoffs backoffs]
    (let [[event port] (async/alts! (filter identity [ret t]))]
      (condp = port
        t (do (swap! >metrics update :acquire #(-> %
                                                   (update :invoked inc)
                                                   (update :pending inc)))
              (recur #?(:clj (async/thread (f)) :cljs (async/go (f))) nil backoffs))
        ret (do (swap! >metrics update-in [:acquire :pending] dec)
                (if (some? event)
                  event
                  (do (swap! >metrics update-in [:acquire :failed] inc)
                      (when-let [backoff (first backoffs)]
                        (swap! >metrics update :acquire #(-> %
                                                             (update :retries inc)
                                                             (assoc :last-backoff backoff)))
                        (recur nil (async/timeout backoff) (rest backoffs))))))))))

;; A channel-like type that coordinates the supply of fresh ephemeral values.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Ephemeral [acquire >k kf vf ef rf out trigger >metrics]
  impl/ReadPort
  (take! [this fn1-handler]
    (swap! >metrics update :takes inc)
    (let [ret (impl/take! out fn1-handler)] ; ret is nil if take was enqueued
      (when (nil? ret)
        (swap! >metrics update :takes-deferred inc)
        (async/offer! trigger true))
      ret))
  impl/WritePort
  (put! [this v fn1-handler]
    (swap! >metrics update :puts inc)
    (impl/put! out v fn1-handler))
  impl/Channel
  (close! [this]
    (swap! >metrics assoc :closed? true :closed-at (now))
    (async/close! trigger)
    (impl/close! out))
  (closed? [this] (impl/closed? out))
  ;; Inspired by https://clojure.atlassian.net/browse/ASYNC-102
  #?@(:clj (clojure.lang.IDeref ; This interface is semantically inappropriate for ClojureScript, right?
            (deref [this]
                   (let [p (promise)]
                     (async/take! this (fn [x] (deliver p x)))
                     (deref p)))
            clojure.lang.IBlockingDeref
            (deref [this timeout fallback]
                   (let [t (async/timeout timeout)
                         [val port] (async/alts!! [t this])]
                     (if (= this port) val fallback)))
            clojure.lang.IPending
            (isRealized [this] (boolean (async/poll! this)))))
  clojure.lang.IFn
  (invoke [this] (try (when-some [result (acquire @>k)]
                        (let [[k v e r] ((juxt kf vf ef rf) result)]
                          (reset! >k k)
                          (if (and v ((some-fn nil? pos?) e)) ; fresh?
                            (do (swap! >metrics update :fresh-acquisitions inc)
                                (async/put! this v)
                                [e r])
                            (do (swap! >metrics update :stale-acquisitions inc)
                                [nil 0]))))
                      (catch Exception e
                        (swap! >metrics assoc :last-acquisition-exception e)
                        nil)))
  Stateful
  (inspect [this] [@>k @>metrics (async/poll! this) (impl/closed? out)])
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Ephemeral " (pr-str v) ">")
                     "#<Ephemeral >")))

(def capped-exponential-backoff (concat (take 16 (iterate (partial * 2) 1)) (repeat 60000)))

(defn create
  "Create a channel-like ephemeral type that will be iteratively supplied
  perishable values by invoking the provided `acquire` function.

  The `acquire` function is passed the current continuation token (k) and should
  return a non-nil value.  If acquire returns nil or throws an exception, the
  optional `backoffs` sequence can be configured to manage delays before each
  retry.  The default is exponential backoff capped at 60s.

  The return value of `acquire` is interpreted by optional functions.

   :vf - fn of 'ret' -> 'v', the ephemeral's acquired value, default 'identity'
   :kf - fn of 'ret' -> 'next-k', default 'identity'
   :ef - fn of 'ret' -> time (in ms) before value expires, default nil (never expires)
   :rf - fn of 'ret' -> time (in ms) before value should be refreshed, default nil (never refresh)
   :initk - the initial token value passed to `acquire`, default 'nil'
   :backoffs - seqable of delays, in ms, default exponential backoff capped at 60s.

  The ephemeral's value is available by either taking from the ephemeral as a
  channel or dereferencing the ephemeral.  In either case, if no fresh value is
  available, acquirers will be blocked until a value becomes available.

  If `rf` is nil, the ephemeral will not pre-emptively acquire a value.
  Instead, like a clojure delay, acquisition will only be invoked on an attempt
  to access the ephemeral value.  This on-demand acquisition will continue even
  if the acquired value expires.

  If the ephemeral channel is closed all resources are freed and no further
  updates to the ephemeral will be attempted."
  [acquire & {:keys [initk kf vf ef rf backoffs]
              :or {initk nil
                   vf identity
                   kf identity
                   ef nil ; never expires
                   rf nil ; never refreshes; trigger acquire only when unrealized value is accessed
                   backoffs capped-exponential-backoff}}]
  {:pre [(fn? acquire) vf kf (seqable? backoffs)]}
  (let [out (async/chan (resettable-promise-buffer ::unrealized))
        trigger (async/chan (async/dropping-buffer 1))
        ef' (or ef (constantly nil))
        rf' (or rf (constantly nil))
        >metrics (atom {:created-at (now)
                        :takes 0 :puts 0
                        :takes-deferred 0
                        :acquisitions 0
                        :stale-acquisitions 0
                        :fresh-acquisitions 0
                        :expirations 0})
        eph (->Ephemeral acquire (atom initk) kf vf ef' rf' out trigger >metrics)]
    (async/go-loop [in nil e-alarm nil r-alarm nil called-at nil]
      (let [[event port] (async/alts! (filter identity [in e-alarm r-alarm trigger]))
            now (now)]
        (if-let [[in ea ra c] (condp = port
                                e-alarm (do (swap! >metrics #(-> %
                                                                 (update :expirations inc)
                                                                 (assoc :expired-at now)))
                                            (async/put! out ::unrealized)
                                            [in nil r-alarm called-at])
                                trigger (when event
                                          (if (or in (async/poll! out)) ; are we already fetching or did we just acquire a value?
                                            [in e-alarm r-alarm called-at] ; no-op
                                            [(insist eph backoffs >metrics) e-alarm nil now]))
                                r-alarm [(insist eph backoffs >metrics) e-alarm nil now]
                                in (when event
                                     (let [[expires-in refresh-in] event]
                                       (swap! >metrics #(-> %
                                                            (update :acquisitions inc)
                                                            (assoc :acquired-at now)
                                                            (dissoc :expired-at)))
                                       (let [latency (- now called-at)
                                             refresh-alarm (when refresh-in
                                                             (async/timeout (max (- refresh-in latency)
                                                                                 (if expires-in (long (/ expires-in 2)) 0))))]
                                         [nil (when expires-in (async/timeout expires-in)) refresh-alarm nil]))))]
          (recur in ea ra c)
          (swap! >metrics assoc :event-loop-closed-at now))))
    (when rf (async/offer! trigger true))
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

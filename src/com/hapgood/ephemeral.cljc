(ns com.hapgood.ephemeral
  (:require [clojure.pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [com.hapgood.ephemeral.insist :as-alias insist]
            [com.hapgood.ephemeral.buffers :refer [resettable-promise-buffer]]))

(defprotocol Eventful
  (events [this] "Return the channel in which events related to this are reported."))

(defn- now [] #?(:clj (System/currentTimeMillis) :cljs (inst-ms (js/Date.))))

(defn- insist
  "Resiliently evaluate the function `f`.  Returns a channel whose only value
  is the return of `f`.  If `f` returns nil or throws an exception, it is
  retried after a delay (in ms) taken from `backoffs`."
  [f backoffs =events]
  {:pre [(seqable? backoffs)]}
  (async/put! =events [::insist/started])
  (async/put! =events [::insist/wait-starting])
  (async/go-loop [ret (f) t nil backoffs backoffs]
    (let [[event port] (async/alts! (filter identity [ret t]))]
      (condp = port
        t (do (async/put! =events [::insist/backoff-ended])
              (async/put! =events [::insist/wait-starting])
              (recur (f) nil backoffs))
        ret (do (async/put! =events [::insist/wait-ended])
                (if (some? event)
                  (do (async/put! =events [::insist/ended])
                      event)
                  (if-let [backoff (first backoffs)]
                    (do (async/put! =events [::insist/backoff-starting {:backoff backoff}])
                        (recur nil (async/timeout backoff) (rest backoffs)))
                    (do (async/put! =events [::insist/ended])
                        nil))))))))

;; A channel-like type that coordinates the supply of fresh ephemeral values.
;; TODO: https://blog.klipse.tech/clojurescript/2016/04/26/deftype-explained.html
(deftype Ephemeral [acquire >k kf vf ef rf out trigger events]
  impl/ReadPort
  (take! [this fn1-handler]
    (async/put! events [::take])
    (let [ret (impl/take! out fn1-handler)] ; ret is nil if take was enqueued
      (when (nil? ret)
        (async/put! events [::take-deferred])
        (async/offer! trigger true))
      ret))
  impl/Channel
  (close! [this]
    (async/close! trigger)
    (async/put! events [::closed])
    (impl/close! out))
  (closed? [this] (impl/closed? out))
  ;; Inspired by https://clojure.atlassian.net/browse/ASYNC-102
  #?@(:clj (clojure.lang.IDeref
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
  #?(:clj clojure.lang.IFn :cljs IFn)
  (#?(:clj invoke :cljs -invoke)
    [this] (async/go (try
                       (let [c (async/chan)]
                         (acquire @>k c)
                         (when-some [result (async/<! c)]
                           (let [[k v e r :as x] ((juxt kf vf ef rf) result)]
                             (async/put! events [::acquisition])
                             (reset! >k k)
                             (if (and v ((some-fn nil? pos?) e)) ; fresh?
                               (do (async/put! events [::fresh-acquisition])
                                   (async/put! out v)
                                   [e r])
                               (do (async/put! events [::stale-acquisition])
                                   [nil 0])))))
                       (catch #?(:clj Exception :cljs js/Error) e
                         (async/put! events [::acquisition-exception {:exception e}])
                         nil))))
  Eventful
  (events [this] events)
  Object
  (toString [this] (if-let [v (async/poll! this)]
                     (str "#<Ephemeral " (pr-str v) ">")
                     "#<Ephemeral >")))

(def capped-exponential-backoff
  "A infinite exponentially increasing sequence of integers capped at 60000"
  (concat (take 16 (iterate (partial * 2) 1)) (repeat 60000)))

(defn create
  "Create a channel-like ephemeral type that will be iteratively supplied
  perishable values by invoking the provided `acquire` function.

  The `acquire` function is passed the current continuation token (k) and should
  return a non-nil value.  If acquire returns nil or throws an exception, the
  optional `backoffs` sequence can be configured to manage delays before each
  retry.  The default is an infinite exponential backoff capped at 60s.

  The return value of `acquire`, 'ret', is interpreted by optional functions.

   :vf - fn of 'ret' -> 'v', the ephemeral's acquired value, default 'identity'
   :kf - fn of 'ret' -> 'next-k', default 'identity'
   :ef - fn of 'ret' -> time (in ms) before value expires, default nil (never expires)
   :rf - fn of 'ret' -> time (in ms) before value should be refreshed, default nil (never refresh)

  Other options are:

   :initk - the initial token value passed to `acquire`, default 'nil'
   :backoffs - seqable of delays, in ms, default exponential backoff capped at 60s.

  The ephemeral's value is available by either taking from the ephemeral as a
  channel or dereferencing the ephemeral (Clojure-only).  In either case, if no
  fresh value is available, acquirers will be blocked until a fresh value is
  acquired.

  If `rf` is nil, the ephemeral will not pre-emptively acquire a value upon
  creation.  Instead, like a clojure delay, acquisition will only be triggered
  by attempts to access the ephemeral value.  This on-demand acquisition will
  repeat if the acquired value expires.

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
        events (async/chan (async/sliding-buffer 20))
        >k (atom initk)
        eph (->Ephemeral acquire >k kf vf ef' rf' out trigger events)]
    (async/put! events [::event-loop-starting])
    (async/go-loop [in nil e-alarm nil r-alarm nil called-at nil]
      (let [[event port] (async/alts! (filter identity [in e-alarm r-alarm trigger]))
            now (now)]
        (if-let [[in ea ra c] (condp = port
                                e-alarm (do (async/put! events [::expiration])
                                            (async/put! out ::unrealized)
                                            [in nil r-alarm called-at])
                                trigger (when event
                                          (if (or in (async/poll! out)) ; are we already fetching or did we just acquire a value?
                                            [in e-alarm r-alarm called-at] ; no-op
                                            [(insist eph backoffs events) e-alarm nil now]))
                                r-alarm [(insist eph backoffs events) e-alarm nil now]
                                in (when event
                                     (let [[expires-in refresh-in] event]
                                       (let [latency (- now called-at)
                                             refresh-alarm (when refresh-in
                                                             (async/timeout (max (- refresh-in latency)
                                                                                 (if expires-in (long (/ expires-in 2)) 0))))]
                                         [nil (when expires-in (async/timeout expires-in)) refresh-alarm nil]))))]
          (recur in ea ra c)
          (do (async/put! events [::event-loop-closed {:k @>k}])
              (async/close! events)))))
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

(defn summarize-events
  "Summarize the events emitted by the given ephemeral `eph`.  Note that only
  buffered and future events will be summarized.  Returns a channel that will
  contain the single event summary once `eph` is closed.

  NB: this function is not suitable for monitoring an ephemeral in production
  since the summary channel is only available after the ephemeral has shut down."
  [eph]
  (let [initial-summary {::acquisition 0
                         ::expiration 0
                         ::take 0}
        summarizer (fn summarizer [summary [event data]]
                     (let [summary (update summary event (fnil inc 0))]
                       (case event
                         ::acquisition-exception (assoc summary ::last-acquisition-exception (:exception data))
                         ::event-loop-closed (assoc summary ::final-k (:k data))
                         ::insist/backoff-starting (let [{:keys [backoff]} data]
                                                     (-> summary
                                                         (assoc ::insist/current-backoff backoff)
                                                         (update ::insist/backoff-accumulated (fnil + 0) backoff)
                                                         (update ::insist/total-backoff-accumulated (fnil + 0) backoff)))
                         (::insist/started
                          ::insist/ended) (-> summary
                                              (update ::insist/pending? not)
                                              (dissoc ::insist/current-backoff ::insist/backoff-accumulated))
                         (::insist/wait-starting
                          ::insist/wait-ended) (update summary ::insist/waiting? not)
                         summary)))]
    (async/reduce summarizer initial-summary (events eph))))

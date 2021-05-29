(ns com.hapgood.ephemeral
  (:refer-clojure :exclude [ref])
  (:require [clojure.pprint :as pprint]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as protocols]))

(defprotocol IEphemeral
  (capture [ref] [ref fallback]  "Return the value of `ref` if available, otherwise return `fallback`")
  (available? [ref] "Return true if `ref` is available, otherwise false"))

(defprotocol IPerishable
  (refresh! [ref v expires-at] "Refresh the container with the value `v` which will expire at the inst `expires-at`.")
  (expiry [ref] "Return the inst of expiration for the current value of `ref` or nil if it is already expired"))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- delta-t [t0 t1] (- (inst-ms t1) (inst-ms t0)))

;; A reference type whose value may only be available transiently.  The referenced value can be captured
;; when available but it may asynchronously become unavailable (and then available, and then ...).
;; NB: Avoid TOCTOU (time-of-check-time-of-use) race conditions by never holding a captured value -that
;; defeats the ephemeral pattern.  Due to network and processing delays TOCTOU is a potential problem even
;; when not holding captured values.  Consider adding some margin to the expiry of ephemeral values (by
;; expiring them sooner) and/or conservatively refreshing them.
(deftype Ephemeral [pca source]
  IEphemeral
  ;; This is the primary synchronous read interface.  Other sync reads should go through this method.
  (capture [this fallback] (or (some-> @pca async/poll! first) fallback))
  (capture [this] (let [v (capture this ::unavailable)]
                    (if (= ::unavailable v)
                      (let [m "The ephemeral value is not available."]
                        (throw #?(:clj (java.lang.IllegalStateException. m) :cljs (js/Error. m)) ))
                      v)))
  (available? [this] (not= ::unavailable (capture this ::unavailable)))
  IPerishable
  (refresh! [this v expires-at] (async/put! source [v expires-at]))
  (expiry [this] (some-> @pca async/poll! second))
  protocols/ReadPort
  ;; This is the primary asynchronous read interface.
  (take! [this fn-handler] (async/take! @pca fn-handler))
  #?@(:clj
      ;; The clojure.lang.IDeref 'protocol' is implemented since that is common practice for value containers.
      (clojure.lang.IDeref (deref [this] (-> @pca async/<!! first)))
      ;; The IDeref protocol is a semantic mismatch in ClojureScript since it assumes synchronous behavior.  We can't
      ;; offer synchronicity without some escape hatch (see `capture` above) so we don't implement IDeref in ClojureScript.
      #_ #_ :cljs (IDeref (-deref [this] (async/go ))))
  Object
  (toString [this] (let [v (capture this (constantly "Not Available"))]
                     (str "<Ephemeral: " v ">"))))

(defn create
  ([pc] (let [source (async/chan (async/sliding-buffer 1))
              pca (atom pc)]
          ;; coordinate the promise channel atom from [value, expires-at] tuples arriving on the source channel
          (async/go-loop [pc pc]
            (let [[_ expiry] (async/poll! pc)
                  timeout (when expiry (async/timeout (delta-t (now) expiry)))
                  cs (cond-> [source] timeout (conj timeout))
                  [ve port] (async/alts! cs)]
              (if (and (= source port) (not ve)) ; source closed
                (async/close! pc) ; release any parked takes on undelivered promise channel and exit
                (let [pc' (async/promise-chan)]
                  (when ve ; we have a fresh value
                    (assert (async/offer! pc ve)) ; Satisfy parked takes in case we were previously unavailable
                    (assert (async/offer! pc' ve))) ; Do this early to ensure no unecessary parking.
                  (reset! pca pc')
                  (recur pc')))))
          (->Ephemeral pca source)))
  ([] (create (async/promise-chan)))
  ([value expires-at] (let [pc (async/promise-chan)]
                        ;; pre-queue supplied [value, expires-at] tuple to allow synchronous create->capture
                        (when (pos? (delta-t (now) expires-at)) (async/offer! pc [value expires-at]))
                        (create pc))))

#?(:clj
   (do (defmethod clojure.core/print-method Ephemeral
         [ephemeral ^java.io.Writer writer]
         (.write writer (let [v (capture ephemeral ::unavailable)]
                          (if (= v ::unavailable)
                            "#<Ephemeral ?>"
                            (str "#<Ephemeral " (pr-str v) ">")))))
       (defmethod clojure.pprint/simple-dispatch Ephemeral
         [ephemeral]
         (print-method ephemeral *out*)))
   :cljs
   (extend-protocol IPrintWithWriter
     Ephemeral
     (-pr-writer [this writer opts]
       (-write writer (let [v (capture this ::unavailable)]
                        (if (= v ::unavailable)
                          "#<Ephemeral ?>"
                          (str "#<Ephemeral " (pr-str v) ">")))))))

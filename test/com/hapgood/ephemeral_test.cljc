(ns com.hapgood.ephemeral-test
  (:require [com.hapgood.ephemeral :as uat :refer [create] :include-macros true]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [com.hapgood.test-utilities :refer [go-test closing] :include-macros true])
  (:import #?(:clj (java.util Date))))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- t+ [t0 delta] (let [t0ms (inst-ms t0)
                           t1ms (+ t0ms delta)]
                       #?(:clj (java.util.Date. t1ms) :cljs (js/Date. t1ms))))

(add-tap (fn [{e ::uat/event}] (println e)))

(defn- make-supplier
  [n]
  (let [state (atom -1)]
    (fn [c]
      (let [f (fn [] (async/put! c [(swap! state inc) (t+ (now) 1000)]))]
        #?(:clj (do (Thread/sleep n) (f))
           :cljs (js/setTimeout f n))))))

(deftest create-satisfies-channel-protocols
  (closing [eph (create (make-supplier 0))]
           (is (satisfies? impl/ReadPort eph))
           (is (satisfies? impl/Channel eph))))

(deftest supports-metadata
  (closing [eph (create (make-supplier 0))]
           (is (#?@(:clj (instance? clojure.lang.IObj) :cljs (satisfies? IWithMeta)) eph))
           (is (#?@(:clj (instance? clojure.lang.IMeta) :cljs (satisfies? IMeta)) eph))))

(deftest unavailable-ephemeral-cannot-be-captured
  (go-test (closing [e (create (make-supplier 1000))]
                    (is (nil? (first (async/alts! [e (async/timeout 10)]))))))) ; timeout while waiting to read

(deftest ephemeral-can-be-captured-once-supplied-with-value
  (go-test (closing [e (create (make-supplier 10))]
                    (is (zero? (async/<! e))))))  ; rendez-vous

(deftest metadata-records-acquisition
  (go-test (closing [e (create (fn [c] (async/put! c [0 (t+ (now) 10000)])))]
                    (async/<! e)
                    (let [m (meta e)]
                      (is (inst? (m ::uat/acquired-at)))
                      (is (inst? (m ::uat/expires-at)))
                      (is (integer? (m ::uat/latency)))
                      (is (integer? (m ::uat/version)))))))

(deftest supplied-values-expire
  (go-test (closing [e (create (let [state (atom -1)] ; only supply one value
                                 (fn [c] (when (zero? (swap! state inc)) (async/put! c [@state (t+ (now) 100)])))))]
                    (is (zero? (async/<! e))) ; rendez-vous
                    (async/<! (async/timeout 110)) ; wait for the value to expire...
                    (is (nil? (first (async/alts! [e (async/timeout 10)]))))))) ; timeout while waiting to read

(deftest pre-expired-values-trigger-acquire ; without unblocking consumers...
  (go-test (closing [e (create (let [state (atom -5)] ; supply four stale values before supplying a fresh value
                                 (fn [c]
                                   (if (neg? (swap! state inc))
                                     (async/put! c [@state (t+ (now) -100)])
                                     (async/put! c [@state (t+ (now) 1000)])))))]
                    (is (zero? (async/<! e))))))

(deftest exceptions-supplying-value-are-caught-and-retried
  (go-test (closing [e (create (let [state (atom -5)] ; fail four times and then supply a value
                                 (fn [c]
                                   (if (neg? (swap! state inc))
                                     (throw (ex-info "Boom" {}))
                                     (async/put! c [@state (t+ (now) 100)])))))]
                    (is (zero? (async/<! e))))))

(deftest pending-async-captures-are-released-when-source-closes
  (go-test (closing [e (create async/close!)] ; NB: Failure to close promise channel will deadlock this test
                    (is (nil? (async/<! e))))))

(deftest acquire-fn-can-report-failure
  (go-test (closing [e (create (let [state (atom -5)]
                                 (fn [c] (if (zero? (swap! state inc))
                                           (async/put! c [@state (t+ (now) 1000)])
                                           (async/put! c ::unavailable)))))]
                    (is (zero? (async/<! e))))))

(deftest acquire-failure-recorded-in-metadata
  (go-test (closing [e (create (fn [c] (async/put! c ::unavailable)))]
                    (async/<! (async/timeout 100))
                    (let [anomaly (-> e meta ::uat/anomaly)]
                      (is (integer? (anomaly ::uat/backoff)))
                      (is (= ::unavailable (anomaly ::uat/event)))))))

(deftest string-representation
  (closing [e (create (make-supplier 100))]
           ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
           ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
           (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Ephemeral references to be readable data.
  #?(:clj (closing [e (create (make-supplier 100))]
                   (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

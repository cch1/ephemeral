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

(deftest unavailable-ephemeral-cannot-be-captured
  (go-test (closing [e (create (make-supplier 1000))]
                    (is (nil? (first (async/alts! [e (async/timeout 10)]))))))) ; timeout while waiting to read

(deftest ephemeral-can-be-captured-once-supplied-with-value
  (go-test (closing [e (create (make-supplier 10))]
                    (is (zero? (async/<! e))))))  ; rendez-vous

(deftest supplied-values-expire
  (go-test (closing [e (create (let [state (atom -1)] ; only supply one value
                                 (fn [c] (when (zero? (swap! state inc)) (async/put! c [@state (t+ (now) 100)])))))]
                    (is (zero? (async/<! e))) ; rendez-vous
                    (async/<! (async/timeout 110)) ; wait for the value to expire...
                    (is (nil? (first (async/alts! [e (async/timeout 10)]))))))) ; timeout while waiting to read

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

(deftest string-representation
  (closing [e (create (make-supplier 100))]
           ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
           ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
           (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Ephemeral references to be readable data.
  #?(:clj (closing [e (create (make-supplier 100))]
                   (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

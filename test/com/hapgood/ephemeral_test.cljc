(ns com.hapgood.ephemeral-test
  (:require [com.hapgood.ephemeral :as uat :refer [capture available? refresh! expiry stopping stop create] :include-macros true]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [com.hapgood.test-utilities :refer [go-test] :include-macros true])
  (:import #?(:clj (java.util Date))))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- t+ [t0 delta] (let [t0ms (inst-ms t0)
                           t1ms (+ t0ms delta)]
                       #?(:clj (java.util.Date. t1ms) :cljs (js/Date. t1ms))))

#_(add-tap (fn [{e ::uat/event}] (println e)))

(defn- make-supplier
  [n]
  (let [state (atom -1)]
    (fn [c]
      (let [f (fn [] (async/put! c [(swap! state inc) (t+ (now) 1000)]))]
        #?(:clj (do (Thread/sleep n) (f))
           :cljs (js/setTimeout f n))))))

(deftest create-satisfies
  (stopping [eph (create (make-supplier 0))]
            (is (satisfies? com.hapgood.ephemeral/IEphemeral eph))
            (is (satisfies? com.hapgood.ephemeral/IPerishable eph))))

(deftest unavailable-ephemeral-cannot-be-captured
  (go-test (stopping [e (create (make-supplier 1000))]
                     (is (nil? (first (async/alts! [e (async/timeout 10)])))) ; timeout while waiting to read
                     (is (thrown? #?(:clj java.lang.IllegalStateException :cljs js/Error) (capture e))))))

(deftest ephemeral-can-be-captured-once-supplied-with-value
  (go-test (stopping [e (create (make-supplier 10))]
                     (is (zero? (async/<! e))) ; rendez-vous
                     (is (zero? (capture e))))))

(deftest supplied-values-expire
  (go-test (stopping [e (create (let [state (atom -1)] ; only supply one value
                                  (fn [c] (when (zero? (swap! state inc)) (async/put! c [@state (t+ (now) 100)])))))]
                     (is (zero? (async/<! e))) ; rendez-vous
                     (is (zero? (capture e)))
                     (async/<! (async/timeout 110)) ; wait for the value to expire...
                     (is (nil? (first (async/alts! [e (async/timeout 10)])))) ; timeout while waiting to read
                     (is (thrown? #?(:clj java.lang.IllegalStateException :cljs js/Error) (capture e))))))

(deftest exceptions-supplying-value-are-caught-and-retried
  (go-test (stopping [e (create (let [state (atom -5)] ; fail four times and then supply a value
                                  (fn [c]
                                    (if (neg? (swap! state inc))
                                      (throw (ex-info "Boom" {}))
                                      (async/put! c [@state (t+ (now) 100)])))))]
                     (is (zero? (async/<! e)))  ; rendez-vous
                     (is (zero? (capture e))))))

(deftest pending-async-captures-are-released-when-source-closes
  (go-test (stopping [e (create async/close!)]
                     (is (nil? (async/<! e))) ; NB: Failure to close promise channel will deadlock
                     (is (thrown? #?(:clj java.lang.IllegalStateException :cljs js/Error) (capture e))))))

(deftest string-representation
  (stopping [e (create (make-supplier 100))]
            ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
            ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
            (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Ephemeral references to be readable data.
  #?(:clj (stopping [e (create (make-supplier 100))]
                    (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

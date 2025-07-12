(ns com.hapgood.ephemeral-test
  (:require [com.hapgood.ephemeral :as uat :refer [create summarize-events events] :include-macros true]
            [com.hapgood.ephemeral.insist :as-alias insist]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [com.hapgood.test-utilities :refer [go-test closing] :include-macros true])
  (:import #?(:clj (clojure.lang ExceptionInfo) :cljs (cljs.core/ExceptionInfo))))

(defn- now [] #?(:clj (System/currentTimeMillis) :cljs (inst-ms (js/Date.))))

(defn producer [k c] (async/put! c (inc (or k -1))))

(defn lazy-producer
  [n]
  (fn [k c]
    (async/take! (async/timeout n) (fn [_] (producer k c)))))

(deftest instrumented
  (go-test (closing [e (create producer)
                     c (events e)]
             (async/close! e)
             (is (= #{[::uat/event-loop-starting]
                      [::uat/closed]
                      [::uat/event-loop-closed {:k nil}]} (async/<! (async/into #{} c)))))))

(deftest unavailable-ephemeral-cannot-be-captured
  (go-test (let [latency 100]
             (closing [e (create (lazy-producer latency))
                       =metrics (summarize-events e)]
               (is (nil? (first (async/alts! [e (async/timeout (/ latency 2))]))))
               (async/close! e)
               (let [{::uat/keys [take-deferred] :as m} (async/<! =metrics)]
                 (is (= 1 take-deferred)))))))

(deftest ephemeral-blocks-until-available-for-capture
  (go-test (let [start (now)
                 latency 100]
             (closing [e (create (lazy-producer latency))
                       =metrics (summarize-events e)]
               (is (zero? (async/<! e)))
               (is (<= latency (- (now) start))) ; required more than latency to acquire
               (async/close! e)
               (let [{::uat/keys [take fresh-acquisition] :as m} (async/<! =metrics)]
                 (is (= 1 fresh-acquisition))
                 (is (= 1 take)))))))

(deftest can-expire
  (go-test (let [lifetime 10
                 latency 100]
             (closing [e (create (lazy-producer latency) :initk -1 :ef (constantly lifetime))
                       =metrics (summarize-events e)]
               (is (zero? (async/<! e))) ; blocks; access triggers acquisition
               (async/<! (async/timeout (* 5 lifetime))) ; wait for the value to expire...
               (is (nil? (async/poll! e))) ; maybe access triggers acquisition
               (is (pos-int? (async/<! e))) ; blocks; access triggers acquisition
               (async/close! e)
               (let [{::uat/keys [take fresh-acquisition expiration] :as m} (async/<! =metrics)]
                 (is (pos-int? expiration))
                 (is (pos-int? fresh-acquisition))
                 (is (= 3 take)))))))

(deftest ephemeral-options
  (go-test (testing "initk"
             (closing [e (create producer :initk -1)]
               (is (zero? (async/<! e)))))
           (testing "vf"
             (closing [e (create producer :initk 0 :vf (partial * 2))]
               (is (= 2 (async/<! e)))))
           (testing "kf"
             (closing [e (create (fn [k c] (async/put! c {:k (inc k)}))
                                 :initk -1 :kf :k :backoffs nil)]
               (is (= {:k 0} (async/<! e)))))))

#?(:clj (deftest ephemeral-supports-reference-interfaces
          (closing [e (create producer :initk -1 :backoffs nil)]
            (is (zero? (deref e))))
          (let [latency 100]
            (closing [e (create (lazy-producer latency) :initk -1 :backoffs nil)
                      =metrics (summarize-events e)]
              (is (= :timeout (deref e (/ latency 2) :timeout)))
              (is (zero? (deref e (* 2 latency) :timeout)))
              (async/close! e)
              (let [{::uat/keys [take fresh-acquisition] :as m} (async/<!! =metrics)]
                (is (pos-int? fresh-acquisition))
                (is (= 2 take)))))
          (let [e (create producer :initk -1 :backoffs nil)]
            (async/close! e)
            (is (nil? (deref e))))))

(deftest refreshes-non-expiring-values
  (go-test (closing [e (create (fn [k c] (let [k' (inc k)] (async/put! c [k' (if (< k' 2) 1 1000)]))) ; short short long
                               :vf first :initk -1 :kf first :ef (constantly nil) :rf second :backoffs nil)
                     =metrics (summarize-events e)]
             (async/<! (async/timeout 20))
             (is (= 2 (async/<! e)))
             (async/close! e)
             (let [{::uat/keys [take fresh-acquisition expiration] :as m} (async/<! =metrics)]
               (is (pos-int? fresh-acquisition))
               (is (zero? expiration))
               (is (= 1 take))))))

(deftest supports-acquire-on-access-mode
  (go-test (closing [e (create (lazy-producer 10)
                               :initk -1 :ef (constantly nil) :rf nil :backoffs nil)]
             (async/<! (async/timeout 100))
             (is (zero? (async/<! e))))))

(deftest stale-values-re-trigger-acquire ; without unblocking consumers...
  (go-test (closing [e (create (let [lifetimes (concat [-1 -1] (repeat 5 10000))] ; supply two stale values before supplying a fresh value
                                 (fn [k c] (async/put! c [(inc k) (nth lifetimes (inc k))])))
                               :vf first :initk -1 :kf first :ef second :rf second :backoffs nil)
                     =metrics (summarize-events e)]
             (is (= 2 (async/<! e)))
             (async/close! e)
             (let [{::uat/keys [take fresh-acquisition stale-acquisition] :as m} (async/<! =metrics)]
               (is (= 1 fresh-acquisition))
               (is (= 2 stale-acquisition))
               (is (= 1 take))))))

(deftest exceptions-supplying-value-are-handled
  (go-test (closing [e (create (fn [k c] (throw (ex-info "Boom!!" {})))
                               :backoffs nil)
                     =metrics (summarize-events e)]
             (async/take! e (fn [& _])) ; no-op take triggers acquisition
             (async/<! (async/timeout 100))
             (async/close! e)
             (let [{::uat/keys [acquisition acquisition-exception last-acquisition-exception] :as m} (async/<! =metrics)]
               (is (zero? acquisition))
               (is (= 1 acquisition-exception))
               (is (instance? ExceptionInfo last-acquisition-exception))))))

(deftest exceptions-supplying-value-are-caught-and-retried
  (go-test (closing [e (create (let [state (atom -4)] ; fail three times and then supply a value
                                 (fn [k c]
                                   (if (neg? (swap! state inc))
                                     (throw (ex-info "Boom" {}))
                                     (async/put! c [@state 100]))))
                               :vf first)
                     =metrics (summarize-events e)]
             (is (zero? (async/<! e)))
             (async/<! (async/timeout 100))
             (async/close! e)
             (let [{::uat/keys [acquisition acquisition-exception last-acquisition-exception]
                    ::insist/keys [current-backoff backoff-accumulated total-backoff-accumulated] :as m} (async/<! =metrics)]
               (is (nil? current-backoff))
               (is (nil? backoff-accumulated))
               (is (= (+ 1 2 4) total-backoff-accumulated))
               (is (= 1 acquisition))
               (is (= 3 acquisition-exception))
               (is (instance? ExceptionInfo last-acquisition-exception))))))

(deftest closed-channel-signal-failure-and-are-retried
  (go-test (closing [e (create (let [state (atom -4)] ; fail three times and then supply a value
                                 (fn [k c]
                                   (if (neg? (swap! state inc))
                                     (async/close! c)
                                     (async/put! c [@state 100]))))
                               :vf first)
                     =metrics (summarize-events e)]
             (is (zero? (async/<! e)))
             (async/<! (async/timeout 100))
             (async/close! e)
             (let [{::uat/keys [acquisition acquisition-exception]
                    ::insist/keys [current-backoff backoff-accumulated total-backoff-accumulated] :as m} (async/<! =metrics)]
               (is (nil? current-backoff))
               (is (nil? backoff-accumulated))
               (is (= (+ 1 2 4) total-backoff-accumulated))
               (is (= 1 acquisition))))))

(deftest pending-async-captures-are-released-when-source-closes
  (go-test (closing [e (create (lazy-producer 10000) :initk -1 :rf nil)]
             (async/close! e)
             (is (nil? (async/<! e))))))

(deftest string-representation
  (closing [e (create producer :initk -1)]
    ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
    ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
    (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Ephemeral references to be readable data.
  #?(:clj (closing [e (create (lazy-producer 100) :initk -1)]
            (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

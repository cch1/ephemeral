(ns com.hapgood.ephemeral-test
  (:require [com.hapgood.ephemeral :as uat :refer [create inspect] :include-macros true]
            [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as impl]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [com.hapgood.test-utilities :refer [go-test closing] :include-macros true]))

(defn- now [] #?(:clj (System/currentTimeMillis) :cljs (inst-ms (js/Date.))))

(defn- lazy-inc
  [n]
  (fn [k]
    #?(:clj (do (Thread/sleep n) (inc k))
       :cljs (js/setTimeout #(inc k) n))))

(deftest instrumented
  (go-test (closing [e (create (constantly true))]
             (let [[k metrics] (inspect e)]
               (is (map? metrics))))))

(deftest unavailable-ephemeral-cannot-be-captured
  (go-test (let [latency 100
                 lazy-zero (fn [& args] #?(:clj (do (Thread/sleep latency) 0)
                                           :cljs (js/setTimeout (constantly 0) latency)))]
             (closing [e (create lazy-zero)]
               (is (nil? (first (async/alts! [e (async/timeout (/ latency 2))]))))
               (let [[_ {:keys [takes-deferred]}] (inspect e)]
                 (is (= 1 takes-deferred)))))))

(deftest ephemeral-blocks-until-available-for-capture
  (go-test (let [start (now)
                 latency 100
                 lazy-zero (fn [& args] #?(:clj (do (Thread/sleep latency) 0)
                                           :cljs (js/setTimeout (constantly 0) latency)))]
             (closing [e (create lazy-zero)]
               (is (zero? (async/<! e)))
               (is (<= latency (- (now) start))) ; required more than latency to acquire
               (let [[_ {:keys [takes fresh-acquisitions] :as m}] (inspect e)]
                 (is (= 1 fresh-acquisitions))
                 (is (= 1 takes)))))))

(deftest can-expire
  (go-test (let [lifetime 10
                 latency 100]
             (closing [e (create (lazy-inc latency) :initk -1 :ef (constantly lifetime))]
               (is (zero? (async/<! e))) ; blocks; access triggers acquisition
               (async/<! (async/timeout (* 5 lifetime))) ; wait for the value to expire...
               (is (nil? (async/poll! e))) ; maybe access triggers acquisition
               (is (pos-int? (async/<! e))) ; blocks; access triggers acquisition
               (let [[_ {:keys [takes fresh-acquisitions expirations] :as m}] (inspect e)]
                 (is (pos-int? expirations))
                 (is (pos-int? fresh-acquisitions))
                 (is (= 3 takes)))))))

(deftest ephemeral-options
  (testing "initk"
    (closing [e (create inc :initk -1)]
      (is (zero? (deref e)))))
  (testing "vf"
    (closing [e (create (constantly 1) :vf (partial * 2))]
      (is (= 2 (deref e)))))
  (testing "kf"
    (closing [e (create (fn [k] {:k (inc k)}) :initk -1 :kf :k :backoffs nil)]
      (is (= {:k 0} (deref e))))))

#?(:clj (deftest ephemeral-supports-reference-interfaces
          (closing [e (create inc :initk -1 :backoffs nil)]
            (is (zero? (deref e))))
          (let [latency 100]
            (closing [e (create (lazy-inc latency) :initk -1 :backoffs nil)]
              (is (= :timeout (deref e (/ latency 2) :timeout)))
              (is (zero? (deref e (* 2 latency) :timeout)))
              (let [[_ {:keys [takes fresh-acquisitions expirations] :as m}] (inspect e)]
                (is (pos-int? fresh-acquisitions))
                (is (= 2 takes)))))))

(deftest refreshes-non-expiring-values
  (go-test (closing [e (create (fn [k] (let [k' (inc k)] [k' (if (< k' 2) 1 1000)])) ; short short long
                               :vf first :initk -1 :kf first :ef (constantly nil) :rf second :backoffs nil)]
             (async/<! (async/timeout 20))
             (is (= 2 (async/<! e)))
             (let [[_ {:keys [takes fresh-acquisitions expirations] :as m}] (inspect e)]
               (is (pos-int? fresh-acquisitions))
               (is (zero? expirations))
               (is (= 1 takes))))))

(deftest supports-acquire-on-access-mode
  (go-test (closing [e (create (lazy-inc 10)
                               :initk -1 :ef (constantly nil) :rf nil :backoffs nil)]
             (async/<! (async/timeout 100))
             (is (zero? (async/<! e))))))

(deftest pre-expired-values-trigger-acquire ; without unblocking consumers...
  (go-test (closing [e (create (let [lifetimes (concat (repeat 4 -1) (repeat 5 10000))] ; supply four stale values before supplying a fresh value
                                 (fn [k] [(inc k) (nth lifetimes (inc k))]))
                               :vf first :initk -1 :kf first :ef second :rf second :backoffs nil)]
             (is (= 4 (async/<! e))))))

(deftest exceptions-supplying-value-are-caught-and-retried
  (go-test (closing [e (create (let [state (atom -5)] ; fail four times and then supply a value
                                 (fn [k]
                                   (if (neg? (swap! state inc))
                                     (throw (ex-info "Boom" {}))
                                     [@state 100])))
                               :vf first)]
             (is (zero? (async/<! e))))))

(deftest pending-async-captures-are-released-when-source-closes
  (go-test (closing [e (create (lazy-inc 10000) :initk -1 :rf nil)
                     out (async/go (async/<! e))]
             (async/close! e)
             (is (nil? (async/<! out))))))

(deftest acquire-fn-can-report-failure
  #_(go-test (closing [e (create (let [state (atom -5)]
                                   (fn [k] (if (zero? (swap! state inc))
                                             [@state 1000]
                                             ::unavailable)))
                                 HERE
                                 :somef sequential? :backoffs nil)]
               (is (zero? (async/<! e))))))

(deftest string-representation
  (closing [e (create (lazy-inc 1000) :initk -1)]
    ;; Use containing brackets to demarcate the psuedo-tag and value from surrounding context
    ;; String must start with a `#` to prevent brackets from confusing some parsing (paredit? clojure-mode?)
    (is (re-matches #"#<.+>" (str e)))))

(deftest cannot-be-printed-as-data
  ;; One should never expect Ephemeral references to be readable data.
  #?(:clj (closing [e (create (lazy-inc 100) :initk -1)]
            (is (thrown? java.lang.IllegalArgumentException (binding [*print-dup* true] (pr-str e)))))))

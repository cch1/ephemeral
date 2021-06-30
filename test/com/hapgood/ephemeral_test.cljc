(ns com.hapgood.ephemeral-test
  (:require [com.hapgood.ephemeral :as uat :refer [capture available? refresh! expiry create]]
            [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing #?(:cljs async)]])
  (:import #?(:clj (java.util Date))))

(defn- now [] #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn- t+ [t0 delta] (let [t0ms (inst-ms t0)
                           t1ms (+ t0ms delta)]
                       #?(:clj (java.util.Date. t1ms) :cljs (js/Date. t1ms))))

(deftest create-satisfies
  (let [eph (create)]
    (is (satisfies? com.hapgood.ephemeral/IEphemeral eph))
    (is (satisfies? com.hapgood.ephemeral/IPerishable eph))))

(deftest uninitialized-ephemeral-cannot-be-captured
  (let [e (create)]
    (is (thrown? #?(:clj java.lang.IllegalStateException :cljs js/Error) (capture e)))))

(deftest initial-expired-value-cannot-be-captured-synchronously
  ;; This test exposes unnecessary (and semantically broken) asynchronous expiring of initial values
  (let [t #inst "1970-01-01T00:00:00.000-00:00"
        e (create 72 t)]
    (is (thrown? #?(:clj java.lang.IllegalStateException :cljs js/Error) (capture e)))))

(deftest initial-value-synchronously-available
  ;; This test exposes unnecessary (and semantically broken) asynchronous loading of initial values
  (let [t #inst "2100-01-01T00:00:00.000-00:00"
        e (create 72 t)]
    (is (= 72 (capture e)))))

(deftest initial-value-expires
  (let [t (t+ (now) 50)
        e (create 72 t)]
    #?@(:clj
        ((Thread/sleep 100)
         (is (thrown? java.lang.IllegalStateException (capture e))))
        :cljs
        ((async done (js/setTimeout (fn [] (is (thrown? js/Error (capture e))) (done)) 100))))))

(deftest value-can-be-refreshed
  (let [e (create 0 (t+ (now) 10))]
    (refresh! e 1 (t+ (now) 10))
    #?@(:clj
        ((Thread/sleep 1) (is (= 1 (capture e))))
        :cljs
        ((async done (js/setTimeout (fn [] (is (= 1 (capture e))) (done)) 1))))))

(deftest initial-value-available-asynchronously
  (let [e (create 0 (t+ (now) 1000))]
    #?@(:clj
        ((is (= 0 (async/<!! e))))
        :cljs
        ((async done (async/go (is (= 0 (async/<! e))) (done)))))))

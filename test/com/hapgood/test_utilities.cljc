;; Reference: https://code.thheller.com/blog/shadow-cljs/2019/10/12/clojurescript-macros.html
(ns com.hapgood.test-utilities
  "Support utilities for tests"
  (:require [clojure.core.async :as async]
            [clojure.test :as test]))

(def ^:dynamic *timeout* 30000)

(defmacro go-test
  "Asynchronously execute the test body (in a go block)"
  [& body]
  (if (:ns &env)
    ;; In ClojureScript we execute the body as a test/async body inside a go block.
    `(test/async done# (async/go (async/alt!
                                   (async/go (do ~@body)) nil ; no-op, the test should have made appropriate assertions
                                   (async/timeout *timeout*) (test/is false "Timed out waiting for test to complete."))
                                 (done#)))
    ;; In Clojure we block awaiting the completion of the async test block
    `(async/<!! (async/go (async/alt!
                            (async/go (do ~@body)) nil ; no-op, the test should have made appropriate assertions
                            (async/timeout 5000) (test/is false "Timed out waiting for test to complete."))))))

(defmacro closing
  "binding-pair => [name init]

  Evaluates body in a try expression with `name` bound to the value of the
  init (presumably a channel), and a finally clause that calls closes `name`."
  [binding-pair & body]
  `(let ~binding-pair
     (try
       (do ~@body)
       (finally
         (async/close! ~(binding-pair 0))))))

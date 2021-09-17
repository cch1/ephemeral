;; Reference: https://code.thheller.com/blog/shadow-cljs/2019/10/12/clojurescript-macros.html
(ns com.hapgood.test-utilities
  "Support utilities for tests"
  (:require [clojure.core.async :as async]
            [clojure.test :as test]))

(defmacro go-test
  "Asynchronously execute the test body (in a go block)"
  [& body]
  (if (:ns &env)
    ;; In ClojureScript we execute the body as a test/async body inside a go block.
    `(test/async done# (async/go (do ~@body) (done#)))
    ;; In Clojure we block awaiting the completion of the async test block
    `(async/<!! (async/go (do ~@body)))))

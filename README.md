# Ephemeral
An ephemeral is a channel-like Clojure(Script) type that coordinates the acquisition and use of "perishable" values.

Unlimited takes of a fresh value may be performed without blocking.  When the value expires, takes will block until a fresh value is available.  The ephemeral cycles between these two states (fresh, expired) based on the timely supply of values by the `acquire` function.

While this library is designed for values that "hard" expire (like OAuth tokens), it also supports values that "soft" expire (e.g. samples of signals, stock quotes, etc).

In Clojure only, it is also possible to dereference the ephemeral to obtain the current (fresh) value.  If no fresh value is available, dereferencing will block.

### The acquire function
The user-supplied `acquire` function is responsible for periodically supplying the ephemeral with fresh values.  It is called with a caller-managed key and should return a channel that will eventually receive a fresh value.  The ephemeral coordinates the calling of the acquire function based on observed latency and the expiration of the current value.  If the acquire function throws synchronously it will be retried after a suitable backoff delay.  The acquire function can also asynchronously signal a failure by closing the channel.  The function will again be retried after a suitable backoff delay.

The acquired value itself is opaque to the ephemeral code, but it is the source of critical information: the value made available to consuming callers; the key value retained for the next iteration of acquire; the delay value (in milliseconds) before the value has expired; and the delay value (in milliseconds) before the next acquire invocation should be initiated.  These four interpretations of the opaque value returned by the acquire function are performed by optional functions provided when the ephemeral is created.  This mirrors the behavior of [Clojure's iteration function](https://clojuredocs.org/clojure.core/iteration).

In addition to the acquire function, the ephemeral library is configurable with these options:

#### kf and initk
The acquire function may need to track state across iterations.  The value returned by acquire is passed to `kf` to compute the key that will be passed to acquire on the next iteration.  The first time acquire is called, it is passed the initial key `initk`.  The `kf` function should not block.

#### vf
The value returned by acquire is passed to `vf` to compute the value that will be made available to the clients of the ephemeral via async takes or dereferencing (Clojure-only).  The `vf` function should not block or return nil.

#### ef
The value returned by acquire is passed to `ef` to compute the value (in milliseconds) used to scheduled the expiration of the ephemeral value.  If `ef` is nil or returns nil, the acquired value will never expire.  It can still be refreshed.  The `ef` function should not block.

#### rf
When the ephemeral does not have a fresh value, any read attempt (via an async take or dereference) will provoke an attempt to acquire a fresh value.  An ephemeral can thus be configured to only acquire values "on demand".  If/when the acquired value expires, no refresh will occur.  In many cases it is desirable to always have a fresh value on hand and the `rf` function exists to schedule asynchronous refreshing of the value.  The value returned by acquire is passed to `rf` to compute the value (in milliseconds) used to schedule the refresh of the ephemeral value.  If `rf` is nil or returns nil, the acquired value will not be scheduled for refresh.  The `rf` function should not block.

The ephemeral library will advance any scheduled refresh based on observed latency.  For example, if the first acquisition takes 100ms and the scheduled refresh is in one hour, then the following refresh will be initiated 100ms before the hour expires.

#### backoffs
Failure to acquire a fresh value has dire consequences for consumers of an ephemeral.  To mitigate the inevitable failures, if the acquire function fails, the ephemeral can be configured to backoff and try again.  An exponential backoff capped at 60 seconds is used by default, but the caller can supply a custom strategy in the form of a sequence of integer millisecond delays, e.g. `(constantly 10000)` using the `backoff` option.

### TOCTOU
Avoid time-of-check-time-of-use (TOCTOU) race conditions by never holding a captured value.  Instead, take a value at the moment of use.  Due to network and processing delays TOCTOU is a potential problem even when not holding acquired values.  In extreme cases, you might need to schedule refresh well before the expiration of the ephemeral value.

### Example usage
The ephemeral pattern is well-suited for managing the fresh supply of expiring credentials obtained from a remote service.  It is often the case that using expired credentials is worse than blocking until fresh credentials are available.

``` clojure
(require '[com.hapgood.ephemeral :as ephemeral])

(defn acquire
  [k]
  (let [c (async/chan)]
    (http/get token-server-url
              {:body (construct-request-body k)
               :on-success (fn [response]
                             (let [token (extract-token response)]
			       (async/put! c token)))
	       :on-failure (fn [] (async/close! c))})
    c))

(def e (ephemeral/create acquire
                         :initk shared-secret :kf :refresh-token
                         :vf :access-token
                         :ef :token-expiration :rf :token-expiration))

(let [access-token (async/<!! e)] ; block until fresh credentials are available
  (http/post secured-service
             {:headers {:X-My-Token access-token}}
             "Somehing"))
```

### Monitoring and Instrumentation
Each ephemeral makes available a core.async channel on which it places every significant event in its lifecycle.  Events can 
be processed to generate metrics, post logs and even persist the ephemeral's state at shutdown.  Events are always a tuple of the form:

`(event-name optional-attributes-map)`

### Serialization
Ephemerals are not values and are not suitable for serialization.

### Shutdown
To free up the resources used by the ephemeral, close it as you would close a core.async channel.

# Ephemeral
An ephemeral is a channel-like Clojure(Script) type that coordinates the acquisition and use of "perishable" values.

Unlimited takes of a fresh value may be performed without blocking.  When the value expires, the ephemeral channel will block on takes until a fresh value is available.  The ephemeral cycles between these two states (fresh, expired) based on the timely supply of values by the `acquire` function.

### The acquire function
The user-supplied `acquire` function is responsible for periodically obtaining fresh values.  It is called with a single parameter: the ephemeral.  The function should acquire a fresh value and put the tuple `(value, expires-at)` on the channel-like ephemeral.  The ephemeral coordinates the calling of acquire based on observed latency and the expiration of the current value.  If the acquire function throws synchronously it will be retried after a suitable backoff delay.  The acquire function can also asynchronously signal a failure to obtain a fresh value by placing a something other than a value satisfying `sequential?` on the ephemeral channel.  The function will again be retried after a suitable backoff delay.

### Backoff
If the acquire function fails, the ephemeral will backoff and try again.  An exponential backoff capped at 30s is used by default, but the caller can supply a custom strategy in the form of a sequence of integer millisecond delays, e.g. `(constantly 10000)`.

### TOCTOU
Avoid time-of-check-time-of-use (TOCTOU) race conditions by never holding a captured value.  Instead, take a value at the moment of use.  Due to network and processing delays TOCTOU is a potential problem even when not holding captured values -consider adding some margin to the expiry of values (by expiring them sooner).

### Example usage
The ephemeral pattern is well-suited for managing the fresh supply of expiring credentials obtained from a remote service.  It is often the case that using expired credentials is worse than blocking until fresh credentials are available.

``` clojure
(require '[com.hapgood.ephemeral :as ephemeral])

(defn acquire
  [e]
  (http/get token-server-url
            {:on-success (fn [response]
                           (let [token (extract-token response)
                                 expires-at (extract-expiry response)]
			     (async/put! e [token expires-at])))
	     :on-failure (fn [] (async/put! e :failed))}))

(def e (ephemeral/create acquire))

...

(let [token (async/<!! e)] ; block until fresh credentials are available
  (http/post secured-service
             {:headers {:X-My-Token token}}
             "Somehing"))
```

### Metadata
Acquisition metrics and failure status are tracked in the metadata of the ephemeral.

### Serialization
Ephemerals are not values and are not suitable for serialization.

### Shutdown
To free up the resources used by the ephemeral, close it as you would close a core.async channel.

### Troubleshooting
The acquire function is automatically rescheduled if synchronously throws an exception or reports a failure asynchronously.  However, if it neither throws an exception synchronously nor places a value on the ephemeral channel it will not ever be rescheduled.

### TODO
1. Consider making the metadata read-only.  Pros: metrics can't be overwritten.  Cons: reduced utility.
2. Consider using mutable fields instead of atoms for internal state.
3. Enhance printing to leverage metatdata.

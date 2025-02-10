<p>
  <img src="https://raw.github.com/goshatch/atproto-clojure/main/resources/logo.png"
  alt="Absolute Terror Protocol"
  style="max-width:300px;" />
</p>

# atproto Clojure SDK

⚠️ Work on this SDK has been paused (see [this bsky post](https://bsky.app/profile/gosha.net/post/3lhstloh6s22n)). If anyone would like to fork the project and continue the work, you are very welcome to do so!

Multi-platform codebase designed to work in Clojure, ClojureScript and
ClojureDart.

## Progress

| Feature      | Status |
| ------------ | ------ |
| http client  | 🟡     |
| identifiers  | 🔴     |
| bsky         | 🔴     |
| crypto       | 🔴     |
| mst          | 🔴     |
| lexicon      | 🔴     |
| identity     | 🔴     |
| streaming    | 🟡     |
| service auth | 🔴     |
| plc          | 🔴     |
| oauth server | 🔴     |

## Usage

### ATProto client

The workflow for utilizing the client is to:

1. Obtain a session by specifying the ATProto endpoint and (optionally) authentication credentials.
2. Use the session to make `query` or `procedure` calls to [ATProto](https://atproto.com/specs/xrpc#lexicon-http-endpoints) or [Bluesky](https://docs.bsky.app/docs/category/http-reference) endpoints.

A session is a thread-safe, stateful object containing the information required to make ATProto HTTP requests.

`query` and `procedure` calls use the "NSID" of the query or procedure, and a Clojure map of parameters/input.

All calls (including the call to `init`) are asynchronous, and return immediately. The return value depends on platform:

- Clojure: a Clojure promise.
- ClojureScript: a core.async channel.
- ClojureDart: a Dart Watchable.

You can also provide a `:channel`, `:callback` or `:promise` keyword option to recieve the return value. Not all options are supported on all platforms.


```clojure
(require '[net.gosha.atproto.client :as at])

;; Unauthenticated client to default endpoint
(def session @(at/init))

;; Unauthenticated client to a particular server
(def session @(at/init :endpoint "https://public.api.bsky.app"))

;; Password-based authenticated client
;; Defaults to looking up and using the identifier's PD server
(def session @(at/init :identifier "me.bsky.social"
                      :password "SECRET"))

;; Bluesky endpoints and their query params can be found here:
;; https://docs.bsky.app/docs/category/http-reference

@(at/query session :app.bsky.actor.getProfile {:actor "gosha.net"})
;; => {:handle "gosha.net",
;;     :displayName "Gosha ⚡",
;;     :did "did:plc:ypjjs7u7owjb7xmueb2iw37u",
;;     ......}

;; Using core.async
(def result (async/chan))
(at/query session :app.bsky.actor.getProfile {:actor "gosha.net"} :channel result)
(async/<!! result)
```

### Jetstream

Connect to Bluesky's [Jetstream service](https://docs.bsky.app/blog/jetstream) to get real-time updates of public network data. Jetstream provides a JSON-based alternative to the binary CBOR firehose, making it easier to work with post streams, likes, follows, and other events.

```clojure
(require '[net.gosha.atproto.jetstream :as jetstream]
         '[clojure.core.async          :as async]
         '[examples.jetstream-analysis :as analysis]))

;; Connect with default settings (subscribes to posts)
(def conn (jetstream/connect-jetstream (async/chan 1024)))

;; Print out a single post (with 5 second timeout)
(let [event (async/alt!!
             (:events conn)    ([v] v)
             (async/timeout 5000) :timeout)]
  (clojure.pprint/pprint event))

;; Start analyzing the stream
(def analysis (analysis/start-analysis conn))

;; Get current statistics about post rates, sizes, etc
(analysis/get-summary @(:state analysis))

;; Save sample messages for offline analysis
(analysis/collect-samples conn
                        {:count    10
                         :filename "samples/my-samples.json"})

;; Cleanup
(analysis/stop-analysis analysis)
(jetstream/disconnect conn)
```

Check out the `examples.jetstream-analysis` namespace for a complete example of stream processing and analysis.

## References

- [Existing SDKs](https://atproto.com/sdks)
- [What goes in to a Bluesky or atproto SDK?](https://github.com/bluesky-social/atproto/discussions/2415)
- [atproto Interop Test Files](https://github.com/bluesky-social/atproto-interop-tests)

## Contribute

Help is very much welcomed!

Before submitting a pull request, please take a look at the [Issues](https://github.com/goshatch/atproto-clojure/issues) to see if the topic you are interested in is already being discussed, and if it is not, please create an Issue to discuss it before making a PR.

For anything else, please reach out on 🦋: [@gosha.net](https://bsky.app/profile/gosha.net)!

## License

MIT, see LICENSE file

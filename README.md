<p>
  <img src="https://raw.github.com/goshatch/atproto-clojure/main/resources/logo.png"
  alt="Absolute Terror Protocol"
  style="max-width:300px;" />
</p>

# atproto Clojure SDK

⚠️ Work on this SDK has been paused (see [this bsky post](https://bsky.app/profile/gosha.net/post/3lhstloh6s22n)). If anyone would like to fork the project and continue the work, you are very welcome to do so!

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

### HTTP client

The client is using [Martian](https://github.com/oliyh/martian/) under the hood to handle the HTTP endpoints [published](https://github.com/bluesky-social/bsky-docs/tree/main/atproto-openapi-types) by the Bsky team in OpenAPI format

```clojure
(require '[net.gosha.atproto.client :as at])

;; Unauthenticated client
(def session (at/init :base-url "https://public.api.bsky.app"))

;; Authenticated client
(def session (at/init :username "me.bsky.social"
                      :app-password "SECRET"
                      :base-url "https://bsky.social"))


;; Bluesky endpoints and their query params can be found here:
;; https://docs.bsky.app/docs/category/http-reference
(let [resp (at/call session :app.bsky.actor.get-profile {:actor "gosha.net"})]
  (select-keys (:body @resp) [:handle :displayName :createdAt :followersCount]))
;; => {:handle "gosha.net",
;; :displayName "Gosha ⚡",
;; :createdAt "2023-05-08T19:08:05.781Z",
;; :followersCount 617}
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

<p>
  <img src="https://raw.github.com/goshatch/atproto-clojure/main/resources/logo.png" 
  alt="Absolute Terror Protocol"
  style="max-width:300px;" />
</p>

# atproto Clojure SDK

Work very much in progress

## Progress

| Feature      | Status |
|--------------|--------|
| http client  | 🟡    |
| identifiers  | 🔴    |
| bsky         | 🔴    |
| crypto       | 🔴    |
| mst          | 🔴    |
| lexicon      | 🔴    |
| identity     | 🔴    |
| streaming    | 🔴    |
| service auth | 🔴    |
| plc          | 🔴    |
| oauth server | 🔴    |

## Usage

### http client

The client is using [Martian](https://github.com/oliyh/martian/) under the hood to handle the HTTP endpoints [published](https://github.com/bluesky-social/bsky-docs/tree/main/atproto-openapi-types) by the Bsky team in OpenAPI format

```clojure
(require '[net.gosha.atproto.core :as atproto]
         '[net.gosha.atproto.client :as atproto-client])

(atproto/init {:baase-url "https://bsky.social"
               :username "someuser.bsky.social"
               :app-password "some-app-password"})

;; This will exchange the app password for a JWT token that can be used to query
;; endpoints that require authentication.
(atproto-client/authenticate!)

;; Bluesky endpoints and their query params can be found here:
;; https://docs.bsky.app/docs/category/http-reference
(let [resp (atproto-client/call :app.bsky.actor.get-profile {:actor "gosha.net"})]
  (select-keys (:body @resp) [:handle :displayName :createdAt :followersCount]))
;; => {:handle "gosha.net",
;; :displayName "Gosha ⚡",
;; :createdAt "2023-05-08T19:08:05.781Z",
;; :followersCount 617}
```

## References

- [Existing SDKs](https://atproto.com/sdks)
- [What goes in to a Bluesky or atproto SDK?](https://github.com/bluesky-social/atproto/discussions/2415)
- [atproto Interop Test Files](https://github.com/bluesky-social/atproto-interop-tests)

## Contribute

Help is very much welcomed! Please reach out on 🦋 [@gosha.net](https://bsky.app/profile/gosha.net)!

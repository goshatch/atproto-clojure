(ns net.gosha.atproto.core
  (:require [clojure.spec.alpha :as s]))

;; Spec for SDK configuration
(s/def ::base-url     string?)
(s/def ::auth-token   (s/nilable string?))
(s/def ::app-password (s/nilable string?))
(s/def ::username     (s/nilable string?))
(s/def ::config       (s/keys :req-un [::base-url]
                              :opt-un [::auth-token ::app-password ::username]))

(defonce config (atom {}))

(defn init
  "Initialise the SDK with configuration. Supports:
  - `:base-url` (required)
  - `:auth-token` (optional)
  - `:app-password` and `:username` (optional, used to generate a token)."
  [options]
  (if (s/valid? ::config options)
    (do
      (reset! config options)
      (println "SDK initialised with configuration:" @config))
    (throw (ex-info "Invalid configuration"
                    {:errors (s/explain-str ::config options)}))))

(comment
  ; Initialise configuration
  (init {:base-url     "https://bsky.social"
         :username     "someuser.bsky.social"
         :app-password "some-app-password"})
  ; Exchange app password for auth token
  (net.gosha.atproto.client/authenticate!)
  ; Make API requests
  (net.gosha.atproto.client/get-req "/xrpc/com.atproto.server.getSession"))
  ; ???
  ; Profit
  

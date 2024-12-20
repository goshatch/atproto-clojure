(ns net.gosha.atproto.client
  (:require
   [clojure.core.async :as a]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [charred.api :as json]
   [clojure.tools.logging :as log]
   [martian.core :as martian]
   [martian.httpkit :as martian-http]))

(s/def ::username string?)
(s/def ::base-url string?)
(s/def ::app-password string?)
(s/def ::openapi-spec string?)
(s/def ::config (s/keys :req-un [::base-url ::openapi-spec]
                  :opt-un [::app-password ::username]))

(def ^{:doc "map of config keys to env vars that can set them"}
  env-keys
  {:openapi-spec "ATPROTO_OPENAPI_SPEC"
   :base-url "ATPROTO_BASE_URL"
   :username "ATPROTO_USERNAME"
   :app-password "ATPROTO_APP_PASSWORD"})

(defn- build-config
  "Build a configuration map based on defaults, env vars and provided values"
  [& {:as user-config}]
  (-> {:openapi-spec "atproto-xrpc-openapi.2024-12-18.json"}
    (into (map (fn [[cfg-key env-var]]
                 (when-let [val (System/getenv env-var)]
                   {cfg-key val}))
            env-keys))
    (merge user-config)))

(defn- add-authentication-header [token]
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (assoc-in ctx [:request :headers "Authorization"]
              (str "Bearer " token)))})

(defn- decode-jwt [token]
  (let [decoder (java.util.Base64/getUrlDecoder)
        payload (second (str/split token #"\."))
        bytes (.getBytes payload "UTF-8")]
    (-> (.decode decoder bytes)
        String.
        json/read-json)))

(defn- expired? [token]
  (let [exp (:exp (decode-jwt token))
        now (quot (inst-ms (java.time.Instant/now)) 1000)]
    (<= exp now)))

;; TODO: automatically refresh expired tokens
(defn authenticate
  "Given an api session, authenticate with the atproto API using an app password
  and return an authenticated api session.

  Note that the session will only work as long as the token is valid. If the
  token expires, authenticate will need to be called again."
  [session]
  (let [{:keys [username app-password openapi-spec base-url]} (::config session)
        response @(martian/response-for
                    session
                    :com.atproto.server.create-session
                    {:identifier username :password app-password})
        token (get-in response [:body :accessJwt])]
    (when-not token
      (if (:status response)
        (throw (ex-info (format "Authorization failed (%s)" (:status response))
                 {:response response}))
        (throw (ex-info "Authorization failed (unknown error)"
                 {:response response}))))
    (martian-http/bootstrap-openapi openapi-spec
      {:server-url base-url
       :interceptors (cons (add-authentication-header token)
                       martian-http/default-interceptors)})))

(defn init
  "Create and return a new api session. Valid configuration options are:

  :username - (optional) Bluesky username.
  :app-password - (optional) Bluesky appplication-specific password.
  :base-url - Bluesky endpoint. Mandatory. Use 'https://public.api.bsky.app'
              for unauthenticated access, 'https://bsky.social' for
              authenticated, or a different endpoint if you know what you're
              doing.
  :openapi-spec - OpenAPI JSON specification. Defaults to the included spec.

  All options can be specifed via an environment variable prefixed by `ATPROTO_`
  (e.g. ATPROTO_BASE_URL)."
  [& {:as options}]
  (let [{:keys [openapi-spec
                base-url
                username
                app-password] :as config} (build-config options)]
    (when-not (s/valid? ::config config)
      (throw (ex-info "Invalid configuration"
               {:errors (s/explain-str ::config config)})))
    (let [session (martian-http/bootstrap-openapi openapi-spec
                    {:server-url base-url})
          session (assoc session ::config config)]
      (if username
        (authenticate session)
        session))))

(defn call
  "Make an HTTP request to the atproto API.

  - `session` The API session returned by `init`.
  - `endpoint` API endpoint for the format :com.atproto.server.get-session
  - `params` Map of params to pass to the endpoint"
  [session endpoint & {:as opts}]
  (martian/response-for session endpoint opts))

(defn call-async
  "Like `call`, but returns a core.async channel instead of a IBlockingDeref.

  If an exception is thrown, it will be placed on the channel."
  [session endpoint & {:as opts}]
  (let [ch (a/promise-chan)]
    (future
      (try
        (a/>!! ch @(call session endpoint opts))
        (catch Exception e
          (a/>!! ch e))))
    ch))

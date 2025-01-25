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
                  :opt-un [::app-password ::username ::openapi-spec]))

(def ^{:doc "map of config keys to env vars that can set them"}
  env-keys
  {:openapi-spec "ATPROTO_OPENAPI_SPEC"
   :base-url "ATPROTO_BASE_URL"
   :username "ATPROTO_USERNAME"
   :app-password "ATPROTO_APP_PASSWORD"})

(def ^{:private true} add-authentication-header
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (let [supplied-auth (get-in ctx [:params :headers "Authorization"])]
              (assoc-in ctx [:request :headers "Authorization"]
                        (if supplied-auth
                          supplied-auth
                          (str "Bearer " (:access @(:tokens (:opts ctx))))))))})

(defn- build-config
  "Build a configuration map based on defaults, env vars and provided values"
  [& {:as user-config}]
  (-> {:openapi-spec "atproto-xrpc-openapi.LATEST.json"}
    (into (map (fn [[cfg-key env-var]]
                 (when-let [val (System/getenv env-var)]
                   {cfg-key val}))
            env-keys))
    (merge user-config)))

(defn- decode-jwt [token]
  (let [decoder (java.util.Base64/getUrlDecoder)
        payload (second (str/split token #"\."))
        bytes (.getBytes payload "UTF-8")]
    (-> (.decode decoder bytes)
        String.
        json/read-json)))

(defn- expired? [token]
  (if (nil? token)
    false
    (let [exp (get (decode-jwt token) "exp")
          now (quot (inst-ms (java.time.Instant/now)) 1000)]
      (<= exp now))))

(defn authenticate
  "Given an api session, authenticate with the atproto API using an app password
  and return an authenticated api session.

  Note that the session will only work as long as the token is valid. If the
  token expires, authenticate will need to be called again."
  [session]
  (let [config (:config (:opts session))
        {:keys [username app-password openapi-spec base-url]} config
        response @(martian/response-for
                    session
                    :com.atproto.server.create-session
                    {:identifier username :password app-password})
        access-token (get-in response [:body :accessJwt])
        refresh-token (get-in response [:body :refreshJwt])]
    (when-not access-token
      (if (:status response)
        (throw (ex-info (format "Authorization failed (%s)" (:status response))
                 {:response response}))
        (throw (ex-info "Authorization failed (unknown error)"
                 {:response response}))))
    (martian-http/bootstrap-openapi
     openapi-spec
     {:server-url base-url
      :tokens (atom {:access access-token :refresh refresh-token})
      :config config
      :interceptors (cons add-authentication-header
                          martian-http/default-interceptors)})))

(defn refresh-token!
  "Given an api session, refresh the access token using the refresh token, and
   update the tokens atom"
  [session]
  (let [tokens (:tokens (:opts session))
        response @(martian/response-for
                   session
                   :com.atproto.server.refresh-session
                   {:headers { "Authorization" (str "Bearer " (:refresh @tokens))}})
        access-token (get-in response [:body :accessJwt])
        refresh-token (get-in response [:body :refreshJwt])]
    (when-not access-token
      (if (:status response)
        (throw (ex-info (format "Failed refreshing token (%s)" (:status response))
                        {:body response}))
        (throw (ex-info "Failed refreshing token (unknown error)"
                        {:body response}))))
    (reset! tokens {:access access-token :refresh refresh-token})
  ))

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
                username] :as config} (build-config options)]
    (when-not (s/valid? ::config config)
      (throw (ex-info "Invalid configuration"
               {:errors (s/explain-str ::config config)})))
    (let [session (martian-http/bootstrap-openapi openapi-spec
                    {:server-url base-url
                     :config config})]
      (if username
        (authenticate session)
        session))))

(defn call
  "Make an HTTP request to the atproto API.

  - `session` The API session returned by `init`.
  - `endpoint` API endpoint for the format :com.atproto.server.get-session
  - `opts` Map of options to pass to the endpoint"
  [session endpoint & {:as opts}]
  (let [tokens (:tokens (:opts session))]
    (when (and tokens
               (expired? (:access @tokens)))
      (refresh-token! session)))
  (let [res @(martian/response-for session endpoint opts)
        error (-> res :body :error)]
    (if (= error "ExpiredToken")
      (do
        (log/info "Access token expired, refreshing.")
        (refresh-token! session)
        (call session endpoint opts))
      res)))

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

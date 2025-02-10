(ns net.gosha.atproto.client
  "Cross-platform API.

  All functions are async, and follow the same pattern for specifying how
  results are returned. Each function takes keyword argument options, and
  callers can specify a :channel, :callback, or :promise which will recieve
  the results.

  Not all mechanisms are supported on all platforms. If no result mechanism is
  specified, a platform-appropriate deferred value (e.g. promise or core.async
  channel will be returned.)"
  (:require [net.gosha.atproto.interceptor :as i]
            [clojure.string :as str]
            #?@(:cljd [] :default [[clojure.core.async :as a]])
            #?(:clj [net.gosha.atproto.impl.jvm :as jvm])))

(defn- success?
  [code]
  (and (number? code)
    (<= 200 code 299)))

(defn- http-error-map
  "Given an unsuccessful HTTP response, convert to an error map"
  [resp]
  {:error (str "HTTP " (:status resp))
   :http-response resp})

(defn- impl-interceptors
  "Return implementation-specific HTTP & content type interceptors"
  []
  #?(:clj [jvm/json-interceptor, jvm/httpkit-handler]))

(declare procedure)

(def auth-header-interceptor
  "Interceptor to add auth headers"
  {::i/name ::auth-headers
   ::i/enter (fn enter-auth-headers [{:keys [auth refresh?] :as ctx}]
               (assoc-in ctx [::i/request :headers "Authorization"]
                 (str "Bearer " (if refresh?
                                  (:refreshJwt @auth)
                                  (:accessJwt @auth)))))})

(def refresh-token-interceptor
  "Interceptor that refreshes and retries expired tokens"
  {::i/name ::refresh-tokens
   ::i/leave (fn leave-refresh-tokens [{:keys [::i/response] :as ctx}]
               (if-not (= "ExpiredToken" (-> response :body :error))
                 ctx
                 (procedure (assoc ctx :refresh? true)
                   :com.atproto.server.refreshSession
                   {}
                   :callback (fn [resp]
                               (if (:error resp)
                                 (i/continue (assoc ctx ::i/response resp))
                                 (do
                                   (reset! (:auth ctx) resp)
                                   (i/continue
                                     (dissoc ctx ::i/response))))))))})


(def xrpc-interceptor
  "Construct an interceptor that converts XRPC requests to HTTP requests against
  the provided endpoint.

  An XRPC request has a :nsid and either :parameters or :input. :parameters
  indicates an XRPC 'query' (GET request) while `input` indicates a 'procedure'
  (POST request)."
  {::i/name ::xrpc
   ::i/enter (fn xrpc-enter [{:keys [::i/request :endpoint] :as ctx}]
               (let [url (str endpoint "/xrpc/" (name (:nsid request)))
                     req (if-let [input (:input request)]
                           (if (empty? input)
                             {:method :post}
                             {:method :post
                              :body (:input request)
                              :headers {"content-type" "application/json"}})
                           {:method :get
                            :query-params (:parameters request)})]
                 (assoc ctx ::i/request (assoc req :url url))))
   ::i/leave (fn xrpc-leave [{:keys [::i/response] :as ctx}]
               (cond
                 (:error response)
                 ctx

                 (success? (:status response))
                 (assoc ctx ::i/response (:body response))

                 (:error (:body response))
                 (assoc ctx ::i/response (:body response))

                 :else
                 (assoc ctx ::i/response (http-error-map response))))})

(def default-public-endpoint "https://public.api.bsky.app")

(declare pd-server)

(def ^:private cfg-endpoint
  "Config interceptor that resolves an endpoint if not provided"
  {::i/name ::resolve-endpoint
   ::i/enter (fn [{:keys [:endpoint :identifier] :as ctx}]
               (if endpoint
                 ctx
                 (if identifier
                   (pd-server identifier :callback
                     #(i/continue
                        (assoc ctx :endpoint %)))
                   (assoc ctx :endpoint default-public-endpoint))))})


(def ^:private cfg-auth-password
  "Config interceptor that performs password-based authentication"
  {::i/name ::cfg-auth-password
   ::i/enter (fn [{:keys [identifier password] :as ctx}]
               (if-not password
                 ctx
                 (procedure ctx :com.atproto.server.createSession
                   {:identifier identifier :password password}
                   :callback
                   (fn [resp]
                     (if (:error resp)
                       (i/continue (assoc ctx ::i/response resp))
                       (i/continue (-> ctx
                                     (assoc :auth (atom resp))
                                     (update :interceptors concat
                                       [auth-header-interceptor
                                        refresh-token-interceptor]))))))))})

(def ^:private cfg-session
  "Config interceptor to clean up and return the current context as a session"
  {::i/enter (fn [ctx]
               (assoc ctx ::i/response (dissoc ctx ::i/queue ::i/stack)))})

(defn init
  "Initialize an ATProto session using the given configuration options.
   Valid config options are:

   - `:endpoint` - Specify a server to connect to. If not specified, will
                   resolve and use the PDS server associated with :identifier`,
                   or else https://public.api.bsky.app.
   - `:identifier` - A user's DID or handle.
   - `:password` - Password for app-password based authentication.
   - `:interceptors` - Custom user-supplied interceptors to modify http requests.

   "
  [& {:as opts}]
  (let [cfg-interceptors [cfg-endpoint
                          cfg-auth-password
                          cfg-session]]
    (i/execute (-> opts
                 (assoc ::i/queue cfg-interceptors)
                 (dissoc :promise :callback :channel))
      (select-keys opts [:promise :callback :channel]))))

(defn- exec-xrpc
  "Given an XRPC request, execute it against the specified session. The
   mechanism for returning results is specified via a :channel, :callback, or
   :promise keyword arg, defaulting to a platform-appropriate type."
  [session request & {:as opts}]
  (i/execute (-> session
               (assoc ::i/queue (concat
                                  [xrpc-interceptor]
                                  (:interceptors session)
                                  (impl-interceptors)))
               (assoc ::i/request request)
               (dissoc ::i/response))
    opts))

(defn query
  "Query using the provided NSID and parameters."
  [session nsid parameters & {:as opts}]
  (exec-xrpc session {:nsid nsid :parameters parameters} opts))

(defn procedure
  "Execute a procedure using the provided NSID and parameters."
  [session nsid input & {:as opts}]
  (exec-xrpc session {:nsid nsid :input input} opts))

(defn resolve-handle
  "Resolve the given handle to a DID."
  [handle & {:keys [endpoint] :as opts
             :or {endpoint default-public-endpoint}}]
  (query {:endpoint endpoint} :com.atproto.identity.resolveHandle
    {:handle handle}
    opts))

(defn id-doc
  "Retrieve the identity document for a handle or DID"
  [handle-or-did & {:keys [endpoint]
                    :as opts
                    :or {endpoint default-public-endpoint}}]
  (let [req (fn [did] {:method :get
                       :url (str "https://plc.directory/" did)})
        interceptor {::i/enter (fn [ctx]
                                 (if (str/starts-with? handle-or-did "did:")
                                   (assoc ctx ::i/request (req handle-or-did))
                                   (resolve-handle handle-or-did :callback
                                     (fn [{:keys [:did]}]
                                       (i/continue (assoc ctx ::i/request
                                                     (req did)))))))
                     ::i/leave (fn [ctx]
                                 (update ctx ::i/response :body))}]
    (i/execute {:endpoint endpoint
                ::i/queue (cons interceptor (impl-interceptors))}
      opts)))

(defn pd-server
  [handle-or-did & {:as opts}]
  (let [[cb val] (i/platform-async opts)]
    (id-doc handle-or-did :endpoint (:endpoint opts)
      :callback (fn [id-doc]
                  (cb (->> (:service id-doc)
                        (filter #(= (:type %) "AtprotoPersonalDataServer"))
                        (first)
                        (:serviceEndpoint)))))
    val))


;; TODO: Validate by reading Lexicon files, and converting to schema/spec/other
(defn validate
  [nsid parameters-or-input]
  (throw (ex-info "Validation not yet implemented" {})))

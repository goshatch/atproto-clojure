(ns net.gosha.atproto.client
  (:require
   [martian.core :as martian]
   [martian.httpkit :as martian-http]
   [net.gosha.atproto.core :as core]))

(def openapi-url "https://raw.githubusercontent.com/bluesky-social/bsky-docs/refs/heads/main/atproto-openapi-types/spec/api.json")

(defn add-authentication-header [token]
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (assoc-in ctx [:request :headers "Authorization"] (str "Bearer " token)))})

(def api (martian-http/bootstrap-openapi
           openapi-url
           {:server-url (:base-url @core/config)
            :interceptors (concat [(add-authentication-header (:auth-token @core/config))]
                                  martian-http/default-interceptors)}))

(defn call
  "Make an HTTP request to the atproto API.
  - `endpoint` API endpoint for the format :com.atproto.server.get-session
  - `opts` Map of params to pass as params to the endpoint"
  ([endpoint] (call endpoint {}))
  ([endpoint opts] (martian/response-for api endpoint opts)))

(defn authenticate!
  "Authenticate with the atproto API using an app password.
   Updates configuration with auth token."
  []
  (let [response (martian/response-for
                   api
                   :com.atproto.server.create-session
                   {:identifier (:username @core/config)
                    :password (:app-password @core/config)})
        token (get-in @response [:body :accessJwt])]
    (swap! core/config assoc :auth-token token)))
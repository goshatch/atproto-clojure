(ns net.gosha.atproto.client
  (:require
   [clojure.data.json :as json]
   [clojure.pprint :refer [pprint]]
   [martian.core :as martian]
   [martian.httpkit :as martian-http]
   [net.gosha.atproto.core :as core]
   [org.httpkit.client :as http]))

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

(defn request
  "Make an HTTP request to the atproto API.
  - `method`: HTTP method (:get, :post, etc.)
  - `endpoint`: API endpoint (relative to `:base-url`)
  - `body`: Request body (optional)
  - `headers`: Additional headers (optional)
  - `retries`: Number of retries for transient failures"
  [method endpoint & [{:keys [body headers retries] :or {retries 3}}]]
  (let [{:keys [base-url auth-token]} @core/config]
    (when-not base-url
      (throw (ex-info "SDK not initialised: missing base-url" {})))
    (let [url (str base-url endpoint)
          options {:method method
                   :url url
                   :headers (merge {"Authorization" (str "Bearer " auth-token)
                                    "Content-Type" "application/json"}
                                   headers)
                   :body (when body (json/write-str body))}]
      (loop [attempt 0]
        (let [response (try
                         {:success true
                          :result @(http/request options)}
                         (catch Exception e
                           {:success false
                            :error e}))]
          (if (:success response)
            (let [result (:result response)]
              (if (<= 200 (:status result) 299)
                (update result :body json/read-str :key-fn keyword)
                (throw (ex-info "API request failed"
                                {:status (:status result)
                                 :body (:body result)}))))
            (if (>= attempt retries)
              (throw (:error response))
              (do
                (Thread/sleep (* 100 (inc attempt)))
                (recur (inc attempt))))))))))

;; Convenience functions
(defn get-req
  "Perform a GET request to the atproto API."
  [endpoint & [options]]
  (request :get endpoint options))

(defn post-req
  "Perform a POST request to the atproto API."
  [endpoint body & [options]]
  (request :post endpoint (merge options {:body body})))

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

(ns net.gosha.atproto.client
  (:require
   [clojure.core.async         :as a]
   [clojure.tools.logging      :as log]
   [martian.core               :as martian]
   [net.gosha.atproto.accounts :as accounts :refer
    [accounts refresh-session!]]))

(defn call
  "Make an HTTP request to the atproto API.
  - `endpoint` API endpoint for the format :com.atproto.server.get-session
  - `params` Map of params to pass to the endpoint"
  ([endpoint params]
   (if-let [default-user (:default-account @accounts)]
     (call default-user endpoint params)
     (throw (ex-info "No default account set" {}))))
  ([handle endpoint params]
   (let [handle (keyword handle)
         spec   (-> @accounts handle :m-spec)
         res   @(martian/response-for spec endpoint params)]
     (if-let [error (-> res :body :error)]
       (do
         (log/warn "API error: " error
                    "message: "  (-> res :body :message)
                    "endpoint: " endpoint
                    "account: "  handle)
         (if (= error "ExpiredToken")
           (do
             (refresh-session! handle)
             (call handle endpoint params))
           res))
       res))))


(defn response-for
  ([endpoint params]
   (if-let [default-user (:default-account @accounts)]
     (response-for default-user endpoint params)
     (throw (ex-info "No default account set" {}))))
  ([handle endpoint params]
   (:body (call handle endpoint params))))


#_
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

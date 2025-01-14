(ns net.gosha.atproto.accounts
  (:require [clojure.spec.alpha    :as s]
            [clojure.tools.logging :as log]
            [martian.core          :as martian]
            [martian.httpkit       :as martian-http]
            [net.gosha.atproto.jwt :as jwt]
            [net.gosha.atproto.specs.core :as type]))



(defonce accounts (atom {:default-account nil
                         :refresh-futures {}}))


(declare schedule-token-refresh!)

(defn create-session-config!
  "Create a new session and config for an account. Stores them in
   -> accounts.handle.session
   -> accounts.handle.config
   Returns the session object on success:
   https://docs.bsky.app/docs/api/com-atproto-server-create-session

   Optional arguments:
   :default - if true, sets this account as the default account"
  [handle & {:keys [default]}]
  (let [handle (keyword handle)
        auth   (-> "./auth.edn" slurp read-string handle)
        config (merge
                {:openapi-spec "atproto-xrpc-openapi.2024-12-18.json"}
                auth)
        _      (when-not (s/valid? ::type/auth-config config)
                 (throw (ex-info "Invalid Auth Configuration"
                                 {:errors (s/explain-str ::type/auth-config auth)})))
        m-spec (martian-http/bootstrap-openapi
                (:openapi-spec config)
                {:server-url (:base-url config)})
        response @(martian/response-for
                   m-spec
                   :com.atproto.server.create-session
                   {:identifier (:username config)
                    :password   (:account-password config)})]
    (if-let [error (-> response :body :error)]
      (throw (ex-info "Failed to create session"
                      {:handle handle
                       :error error}))
      (let [session (:body response)]
        ;; adds :config without password to handle's account
        (swap! accounts assoc-in [handle :config]
               {:base-url     (:base-url config)
                :openapi-spec (:openapi-spec config)})
        ;; adds ATProto :session to handle's account
        (swap! accounts assoc-in [handle :session] session)
        ;; sets default if none set yet, or :default true
        (when (or default (nil? (:default-account @accounts)))
          (swap! accounts assoc :default-account handle))
        ;; automatically schedule token refresh
        (schedule-token-refresh! handle)
        session))))


(defn- add-authentication-header [token]
  {:name ::add-authentication-header
   :enter (fn [ctx]
            (assoc-in ctx [:request :headers "Authorization"]
                      (str "Bearer " token)))})


(defn authenticate
  "Returns martian-spec with authentication headers"
  [handle]
  (let [handle (keyword handle)
        config (-> @accounts handle :config)
        token  (-> @accounts handle :session :accessJwt)]
    (when-not token
      (throw (ex-info "Account token not present. Make sure to login first (create-session)"
                      {:handle handle})))
    (martian-http/bootstrap-openapi (:openapi-spec config)
                                    {:server-url (:base-url config)
                                     :interceptors (cons (add-authentication-header token)
                                                         martian-http/default-interceptors)})))


(defn create-spec!
  "Store an authenticated martian-spec in accounts.handle.m-spec"
  [handle]
  (let [handle (keyword handle)
        m-spec (authenticate handle)]
    (swap! accounts assoc-in [handle :m-spec] m-spec)))


(defn set-default-account!
  "Set the default account handle"
  [handle]
  (let [handle (keyword handle)]
    (if-not (handle @accounts)
      (throw (ex-info "Account does not Exist!" {:account handle}))
      (do
        (swap! accounts assoc :default-account handle)
        handle))))


(defn login!
  "Reads config from auth file for given handle
   Writes to accounts.handle :m-spec :session :config"
  [handle & {:keys [default]}]
  (create-session-config! handle)
  (create-spec! handle)
  (when default
    (set-default-account! handle)))


(defn refresh-session!
  "Refresh an expired session token and return updated session.
   Uses the refreshJwt token from the account's session to request a new accessJwt."
  [handle]
  (let [handle        (keyword handle)
        refresh-token (-> @accounts handle :session :refreshJwt)
        config        (-> @accounts handle :config)
        m-spec  (martian-http/bootstrap-openapi
                 (:openapi-spec config)
                 {:server-url (:base-url config)
                  :interceptors (cons (add-authentication-header refresh-token)
                                      martian-http/default-interceptors)})
        response @(martian/response-for
                   m-spec
                   :com.atproto.server.refresh-session
                   {})]
    (if-let [error (-> response :body :error)]
      (throw (ex-info "Failed to refresh token: "
                      {:account handle
                       :error   error
                       :message (-> response :body :message)}))
      (let [new-session (:body response)]
        (swap! accounts assoc-in [handle :session] new-session)
        (create-spec! handle)
        new-session))))


(defn schedule-token-refresh!
  "Schedule a token refresh before expiration.

   Arguments:
   - handle: The account handle to refresh
   - seconds-before: Number of seconds before expiration to refresh (default: 60)

   Returns a Timer that can be cancelled if needed."
  ([handle]
   (schedule-token-refresh! handle 60))
  ([handle seconds-before]
   (let [handle        (keyword handle)
         session       (-> @accounts handle :session)
         exp-timestamp (-> session :accessJwt jwt/parse-jwt :payload :exp)
         exp-instant   (java.time.Instant/ofEpochSecond exp-timestamp)
         now           (java.time.Instant/now)
         refresh-at    (.minusSeconds exp-instant seconds-before)
         delay-ms      (- (.toEpochMilli refresh-at) (.toEpochMilli now))
         timer        (java.util.Timer. (str "refresh-" handle) true)]
     (when (pos? delay-ms)
       (.schedule timer
                 (proxy [java.util.TimerTask] []
                   (run []
                     (try
                       (refresh-session! handle)
                       (catch Exception e
                         (clojure.tools.logging/error
                          e "Failed to refresh token for " handle))
                       (finally
                         (swap! accounts update :refresh-futures dissoc handle)
                         (.cancel timer)))))
                 delay-ms)
       (swap! accounts assoc-in [:refresh-futures handle] timer)
       timer))))


(defn cancel-refresh!
  "Cancel scheduled token refresh for an account if it exists."
  [handle]
  (when-let [timer (get-in @accounts [:refresh-futures (keyword handle)])]
    (.cancel timer)
    (swap! accounts update :refresh-futures dissoc (keyword handle))
    true))


(defn list-accounts "List all registered account handles" []
  (let [account-keys (set (keys @accounts))
        exclude-set  (set [:default-account :refresh-futures])
        accounts     (into [] (clojure.set/difference account-keys exclude-set))]
    accounts))


(defn remove-account!
  "Remove an account by handle; also cancels any pending refresh futures
   Removing the default account, sets new default if other accounts exist."
  [handle]
  (swap! accounts dissoc handle)
  (cancel-refresh! handle)
  (when (= handle (:default-account @accounts))
    (if-let [next-id (first (list-accounts))]
      (swap! accounts assoc :default-account next-id)
      (swap! accounts assoc :default-account nil))))



(defn remove-all-accounts! []
  (for [account (list-accounts)]
    (remove-account! account)))






(comment

  (login! :zed.earth :default true)
  (login! :zed.zaluka.xyz)

  (-> @accounts :default-account)
  (-> @accounts :refresh-futures)

  (-> @accounts :zed.earth :m-spec)
  (-> @accounts :zed.earth :session)

  (list-accounts)
  (remove-all-accounts!)

  (-> @accounts :zed.zaluka.xyz :session
      :accessJwt jwt/parse-jwt :payload :exp jwt/unix-to-date)
  ;; "2025-01-12 05:26:51"

  (-> @accounts :zed.earth :session
      :accessJwt jwt/expired?)

  (refresh-session! :zed.zaluka.xyz)

  #_{})

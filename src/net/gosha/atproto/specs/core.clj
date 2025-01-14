(ns net.gosha.atproto.specs.core
  (:require [clojure.spec.alpha :as s]
            [martian.core]))

;; common types
(s/def ::base-url     string?)
(s/def ::openapi-spec string?)

;; auth and config spec types
(s/def ::username         string?)
(s/def ::account-password string?)
(s/def ::auth-config (s/keys :req-un [::base-url         ::openapi-spec]
                             :opt-un [::account-password ::username]))




;; -------------------  Single Account Spec -------------------------------
;; Account configuration
(s/def ::account-config (s/keys :req-un [::base-url ::openapi-spec]))

;; ATProto object returned from createSession
(s/def ::session map?)

;; Martian client spec
(s/def ::m-spec (s/spec #(instance? martian.core.Martian %))) ;; Martian client instance

;; Single account entry structure
(s/def ::account (s/keys :req-un [::session
                                  ::m-spec
                                  ::account-config]))
;; ------------------------------------------------------------------------



;; -------------------  Accounts Spec -------------------------------------
;; Timer for refresh futures
(s/def ::refresh-timer   (s/spec #(instance? java.util.Timer %)))
(s/def ::refresh-futures (s/map-of keyword? ::refresh-timer))

;; account handle and default account types
(s/def ::account-handle  keyword?)
(s/def ::default-account ::account-handle)

;; Main accounts atom structure
(s/def ::accounts (s/keys :req-un [::default-account
                                   ::refresh-futures]
                          :opt-un [::account]))
;; ------------------------------------------------------------------------



;; TODO possibly structure specs in separate ns
;; include all specs in specs/core.clj
;; specs/core.clj
;;      /accounts-spec.clj
;;      /auth-spec.clj  .... etc.

(ns net.gosha.atproto.jwt
  (:require
   [net.gosha.atproto.json :as json]
   [clojure.string :as str])
  (:import
   [java.util Base64]))


(defn b64-decode [^String to-decode]
  (String. (.decode (Base64/getDecoder) to-decode)))

(defn parse-jwt [jwt]
  (let [[header payload signature] (str/split jwt #"\.")]
    {:header    (json/parse (b64-decode header))
     :payload   (json/parse (b64-decode payload))
     :signature signature}))

(defn unix-to-date [timestamp]
  (let [format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm:ss")
        date   (java.util.Date. (* 1000 timestamp))]
    (.format format date)))

(defn expired?
  "Check if a JWT token has expired.
   Returns true if the token's expiration timestamp is in the past."
  [jwt]
  (let [exp-timestamp (-> jwt parse-jwt :payload :exp)
        exp-instant   (java.time.Instant/ofEpochSecond exp-timestamp)
        now           (java.time.Instant/now)]
    (.isBefore exp-instant now)))

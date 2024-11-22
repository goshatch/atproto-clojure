(ns com.atproto.client
  (:require [org.httpkit.client :as http]
            [clojure.data.json :as json]))

(defn make-request
  "Make an HTTP request to the ATProto API"
  [method url body headers]
  (let [options {:method method
                 :url url
                 :headers headers
                 :body (when body (json/write-str body))}
        response @(http/request options)]
    (update response :body json/read-str :key-fn keyword)))

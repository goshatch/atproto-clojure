(ns net.gosha.atproto.json
  (:require [charred.api :refer [parse-json-fn]]))

(def parse
  (parse-json-fn
   {:key-fn  keyword
    :profile :immutable}))

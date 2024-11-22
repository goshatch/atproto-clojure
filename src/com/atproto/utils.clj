(ns com.atproto.utils)

(defn validate-response
  "Check if the API response contains an error"
  [response]
  (if (:error response)
    (throw (ex-info "API Error" response))
    response))

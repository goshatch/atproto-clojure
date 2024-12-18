(ns net.gosha.atproto.jetstream
  (:require
   [charred.api           :as json]
   [clojure.core.async    :as async]
   [clojure.tools.logging :as log])
  (:import
   [java.net URI]
   [org.java_websocket.client WebSocketClient]))

(def parse-fn 
  (json/parse-json-fn 
   {:key-fn  keyword
    :profile :mutable ; Use mutable datastructures for better performance
    :async?  false    ; Disable async for small messages
    :bufsize 8192}))  ; Smaller buffer size for small messages}))

(def last-warning-time (atom 0))

(defn warn-rate-limited
  "Logs a warning message at most once every `interval-ms` milliseconds"
  [interval-ms msg]
  (let [current-time (System/currentTimeMillis)]
    (when (> (- current-time @last-warning-time) interval-ms)
      (reset! last-warning-time current-time)
      (log/warn msg))))

(defn create-websocket-client
   "Creates a WebSocket client connected to the Bluesky firehose at the specified URI.
    Messages are parsed as JSON and put onto the output channel with a timeout to prevent
    blocking. Messages will be dropped if the channel is full for more than 100ms.
  
    Arguments:
      uri       - Complete WebSocket URI including query parameters
      output-ch - Channel to receive parsed messages
  
    Returns:
      WebSocketClient instance configured with handlers for connection events"
  [uri output-ch]
  (doto 
    (proxy [WebSocketClient] [(URI. uri)]  ; Just use the URI as provided
      (onOpen [_]
        (log/info "Connected to firehose"))
      
      (onClose [code reason remote]
        (log/info "Disconnected from firehose:" reason))
      
      (onMessage [message]
        (try
          (when-let [data (parse-fn message)]
            (let [put-result (async/alt!!
                              [[output-ch data]] :ok
                              (async/timeout 100) :full)]
              (when (= put-result :full)
                (warn-rate-limited 10000 
                  "Buffer full - dropping message. Consider increasing buffer size or processing messages faster."))))
          (catch Exception e
            (log/error "Parse error:" (.getMessage e)))))
      
      (onError [^Exception ex]
        (log/error "WebSocket error:" (.getMessage ex))))
    (.setConnectionLostTimeout 60)))

(defn connect-jetstream
  "Connects to the Bluesky firehose WebSocket service. Messages are automatically parsed
   from JSON and placed on the provided channel. Uses a non-blocking put with 100ms 
   timeout - messages will be dropped if the channel remains full.
  
   Arguments:
     output-ch - Channel to receive parsed messages
     :service  - Optional base service URL (default: \"wss://jetstream2.us-east.bsky.network\")
     :query    - Optional query string (default: \"?wantedCollections=app.bsky.feed.post\")
  
   Returns:
     Map containing:
       :client - The WebSocket client instance
       :events - The provided output channel"
  [output-ch & {:keys [service query]
                :or   {service "wss://jetstream2.us-east.bsky.network"
                       query   "?wantedCollections=app.bsky.feed.post"}}]
  (let [uri    (str service "/subscribe" query)
        client (create-websocket-client uri output-ch)]
    (.connect client)
    {:client client
     :events output-ch}))

(defn disconnect
  [{:keys [client events]}]
  (when events 
    (async/close! events))
  (when client
    ;; Give a short grace period for any pending operations
    (async/<!! (async/timeout 100))
    (.close client)))

(comment
  ;; Use defaults
  (def conn (connect-jetstream (async/chan 1024)))

  (def conn (connect-jetstream (async/chan 10000)))

  (def conn (connect-jetstream (async/chan (async/sliding-buffer 1024))))
  
  ;; Or specify different query params
  (def conn (connect-jetstream (async/chan 1024) 
                              :query "?wantedCollections=app.bsky.feed.like"))
  
  (let [event (async/alt!!
                (:events conn) ([v] v)
                (async/timeout 5000) :timeout)]
    (clojure.pprint/pprint event))
  
  (disconnect conn)
  ,)

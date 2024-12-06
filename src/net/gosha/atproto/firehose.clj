(ns net.gosha.atproto.firehose
  (:require
   [clojure.core.async    :as async]
   [clojure.tools.logging :as log]
   [clojure.data.json     :as json])
  (:import
   [java.net URI]
   [org.java_websocket.client WebSocketClient]))

(defn parse-firehose-message
  [message]
  (try
    (let [data (json/read-str message :key-fn keyword)]
      (case (:kind data)
        "commit" 
        {:type       :commit
         :did        (:did data)
         :timestamp  (:time_us data)
         :rev        (get-in data [:commit :rev])
         :operation  (get-in data [:commit :operation])
         :collection (get-in data [:commit :collection])
         :record     (get-in data [:commit :record])
         :raw        data}
        
        {:type :unknown
         :data data}))
    (catch Exception e
      (log/error "Parse error:" (.getMessage e))
      nil)))

(defn create-websocket-client
  [uri output-ch]
  (doto 
    (proxy [WebSocketClient] [(URI. (str uri "?wantedCollections=app.bsky.feed.post"))]
      (onOpen [_]
        (log/info "Connected to firehose"))
      
      (onClose [code reason remote]
        (log/info "Disconnected from firehose:" reason))
      
      (onMessage [message]
        (when-let [parsed (parse-firehose-message message)]
          (async/>!! output-ch parsed)))
      
      (onError [^Exception ex]
        (log/error "WebSocket error:" (.getMessage ex))))
    (.setConnectionLostTimeout 60)))

(defn connect-firehose
  "Returns a channel of events"
  [& {:keys [buffer-size service]
      :or   {buffer-size 1024
             service     "wss://jetstream2.us-east.bsky.network"}}]
  (let [output-ch (async/chan buffer-size)
        uri       (str service "/subscribe")
        client    (create-websocket-client uri output-ch)]
    
    (.connect client)
    
    {:client client
     :events output-ch}))

(defn disconnect
  [{:keys [client events]}]
  (when client 
    (.close client))
  (when events 
    (async/close! events)))

(comment

  (def conn (connect-firehose))
  
  (let [event (async/alt!!
                (:events conn) ([v] v)
                (async/timeout 5000) :timeout)]
    (println "\nReceived event:" 
             {:type       (:type event)
              :collection (:collection event)
              :text      (get-in event [:record :text])}))
  
  (disconnect conn)
  
  
  ,)

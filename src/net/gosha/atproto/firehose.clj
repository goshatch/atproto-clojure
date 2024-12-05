(ns net.gosha.atproto.firehose
  (:require
   [clojure.core.async    :as async]
   [clojure.tools.logging :as log]
   [clj-cbor.core         :as cbor])
  (:import
   [java.io ByteArrayInputStream]
   [java.nio ByteBuffer]
   [java.net URI]
   [org.java_websocket.client WebSocketClient]
   [org.java_websocket.handshake ServerHandshake]))

(defn read-varint
  "Read a variable-length integer from the input stream"
  [^ByteArrayInputStream in]
  (loop [shift 0
         result 0]
    (let [b (.read in)]
      (if (neg? b)
        (throw (ex-info "EOF while reading varint" {})))
      (let [val    (bit-and b 0x7F)
            result (bit-or result (bit-shift-left val shift))]
        (if (zero? (bit-and b 0x80))
          result
          (recur (+ shift 7) result))))))

(defn decode-frame
  "Decode a single frame from binary data"
  [^ByteBuffer buffer]
  (try 
    (let [bytes (byte-array (.remaining buffer))]
      (.get buffer bytes)
      (with-open [in (ByteArrayInputStream. bytes)]
        (let [frame-len   (read-varint in)
              frame-bytes (byte-array frame-len)]
          (.read in frame-bytes 0 frame-len)
          (let [decoded (cbor/decode frame-bytes)]
            {:header {:t  (get decoded "t")}
                     :op (get decoded "op")
             :body   decoded}))))
    (catch Exception e
      (log/error "Frame decode error:" (.getMessage e))
      nil)))

(defn create-websocket-client
  [{:keys [uri output-ch]}]
  (proxy [WebSocketClient] [(URI. uri)]
    (onOpen [^ServerHandshake handshake]
      (log/info "Connected to firehose"))
    
    (onClose [code reason remote]
      (log/info "Disconnected from firehose:" reason))
    
    (onMessage [^ByteBuffer buffer]
      (when-let [frame (decode-frame buffer)]
        (async/>!! output-ch frame)))
    
    (onError [^Exception ex]
      (log/error "WebSocket error:" (.getMessage ex)))))

(defn connect-firehose
  "Connect to ATProto firehose and return a channel of events"
  [& {:keys [buffer-size service]
      :or   {buffer-size 1024
             service     "wss://bsky.network"}}]
  (let [output-ch (async/chan buffer-size)
        uri       (str service "/xrpc/com.atproto.sync.subscribeRepos")
        client    (create-websocket-client
                   {:uri       uri
                    :output-ch output-ch})]
    
    (.connect client)
    
    {:client client
     :events output-ch}))

(defn disconnect 
  [{:keys [client events]}]
  (when client 
    (.close client)
    (log/info "Disconnected from firehose"))
  (when events 
    (async/close! events)))

(comment
  ;; Example usage with timeout to avoid hanging
  (def conn (connect-firehose))
  
  ;; Get one message with timeout
  (let [event (async/alt!!
                (:events conn) ([v] v)
                (async/timeout 5000) nil)]
    (when event
      (println "\nReceived event:" (pr-str event))))
  
  (disconnect conn))

(ns net.gosha.atproto.firehose
  (:require
   [clojure.core.async    :as async]
   [clojure.tools.logging :as log]
   [clj-cbor.core         :as cbor])
  (:import
   [java.io  ByteArrayInputStream InputStream]
   [java.nio ByteBuffer]
   [java.net URI]
   [org.java_websocket.client WebSocketClient]
   [org.java_websocket.handshake ServerHandshake]))

(defn buffer->bytes
  "Convert a ByteBuffer to a byte array"
  [^ByteBuffer buffer]
  (let [bytes (byte-array (.remaining buffer))]
    (.get buffer bytes)
    bytes))

(defn decode-varint
  "Decode a variable-length integer from an input stream"
  [^InputStream in]
  (loop [shift  0
         result 0]
    (let [byte (.read in)]
      (if (neg? byte)
        (throw (ex-info "EOF while reading varint" {})))
      (let [result (bit-or result (bit-shift-left (bit-and byte 0x7f) shift))]
        (if (zero? (bit-and byte 0x80))
          result
          (recur (+ shift 7) result))))))

(defn decode-frame
  "Decode a single CBOR frame from binary data"
  [data codec]
  (try
    (let [bytes (if (instance? ByteBuffer data)
                  (buffer->bytes data)
                  data)]
      (with-open [in (ByteArrayInputStream. bytes)]
        (let [length    (decode-varint in)
              remaining (.available in)]
          (log/debug "Frame length:" length "remaining:" remaining)
          (when (>= remaining length)
            (let [buffer (byte-array length)
                  bytes-read (.read in buffer 0 length)]
              (when (= bytes-read length)
                (with-open [frame-stream (ByteArrayInputStream. buffer)]
                  (let [decoded (cbor/decode codec frame-stream)]
                    (log/debug "Decoded frame type:" (type decoded))
                    decoded))))))))
    (catch Exception e
      (log/error e "Error decoding frame")
      nil)))

(defn create-websocket-client
  "Create a WebSocket client for the ATProto firehose"
  [{:keys [uri output-ch codec]}]
  (proxy [WebSocketClient] [(URI. uri)]
    (onOpen [^ServerHandshake handshake]
      (log/info "Connected to firehose"))
    
    (onClose [code reason remote]
      (log/info "Disconnected from firehose:" reason))
    
    (onMessage [data]
      (when-let [decoded (decode-frame data codec)]
        (log/debug "Received message of type:" (type decoded))
        (async/>!! output-ch decoded)))
    
    (onError [^Exception ex]
      (log/error ex "WebSocket error"))))

(defn connect-firehose
  "Connect to ATProto firehose and return a channel of decoded events.
   
   Options:
   - :buffer-size - Size of the async buffer (default 1024)
   - :service     - Service URL (default wss://bsky.network)
   - :codec       - CBOR codec (default cbor/default-codec)"
  [& {:keys [buffer-size service codec]
      :or   {buffer-size 1024
             service     "wss://bsky.network"
             codec       cbor/default-codec}}]
  
  (let [output-ch (async/chan buffer-size)
        uri       (str service "/xrpc/com.atproto.sync.subscribeRepos")
        client    (create-websocket-client
                   {:uri       uri
                    :output-ch output-ch
                    :codec     codec})]
    
    (log/info "Connecting to firehose at" uri)
    (.connect client)
    
    {:client client
     :events output-ch}))

(defn disconnect
  "Cleanly disconnect firehose client"
  [{:keys [client events]}]
  (when client
    (.close client))
  (when events
    (async/close! events)))

(comment
  ;; Test connection
  (def conn (connect-firehose))
  
  ;; Print raw event type and structure
  (let [event (async/<!! (:events conn))]
    (println "Event type:" (type event))
    (println "Event str:" (str event))
    (println "Event pr-str:" (pr-str event)))
  
  ;; Cleanup
  (disconnect conn))

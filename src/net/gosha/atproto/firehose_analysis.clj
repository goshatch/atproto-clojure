(ns net.gosha.atproto.firehose-analysis
  (:require
   [clojure.core.async :as async]
   [net.gosha.atproto.firehose :as firehose])
  (:import
   [java.time Instant Duration]))

(defn calculate-message-sizes [msg]
  {:raw-bytes   (count (str msg))
   :content-len (count (or (get-in msg [:record :text]) ""))})

(defn format-bytes [bytes]
  (cond
    (< bytes 1024)          (format "%d B" bytes)
    (< bytes (* 1024 1024)) (format "%.2f KB" (/ bytes 1024.0))
    :else                   (format "%.2f MB" (/ bytes (* 1024.0 1024.0)))))

(defn create-window [start duration]
  {:start-time         start
   :end-time          (.plus start duration)
   :message-count     0
   :total-raw-bytes   0
   :total-content-len 0
   :max-msg-size      0
   :min-msg-size      Long/MAX_VALUE
   :types             {}})

(defn create-analysis-state
  [window-duration-seconds]
  (let [now      (Instant/now)
        duration (Duration/ofSeconds window-duration-seconds)]
    {:start-time      now
     :stop-time       nil
     :processed-count 0
     :current-window  (create-window now duration)
     :windows         []
     :window-duration duration}))

(defn update-window
  "Update window stats with a new message"
  [window msg]
  (let [{:keys [raw-bytes content-len]} (calculate-message-sizes msg)]
    (-> window
        (update :message-count inc)
        (update :total-raw-bytes + raw-bytes)
        (update :total-content-len + content-len)
        (update :max-msg-size max raw-bytes)
        (update :min-msg-size min raw-bytes)
        (update-in [:types (:type msg)] (fnil inc 0)))))

(defn rotate-window!
  [{:keys [current-window window-duration] :as state}]
  (let [now (Instant/now)]
    (if (.isAfter now (:end-time current-window))
      (let [new-window (create-window now window-duration)]
        (-> state
            (update :windows conj current-window)
            (assoc :current-window new-window)))
      state)))

(defn process-message!
  [state msg]
  (-> state
      rotate-window!
      (update :processed-count inc)
      (update :current-window update-window msg)))

(defn start-analysis
  [conn & {:keys [window-duration-seconds]
           :or   {window-duration-seconds 60}}]
  (let [analysis-ch (async/chan 1024)
        state       (atom (create-analysis-state window-duration-seconds))
        mult        (async/mult (:events conn))]
    
    (async/tap mult analysis-ch)
    
    (async/go-loop []
      (when-let [msg (async/<! analysis-ch)]
        (swap! state process-message! msg)
        (recur)))
    
    {:state       state
     :analysis-ch analysis-ch}))

(defn window-summary
  [{:keys [msg-count total-raw-bytes total-content-len
           max-msg-size min-msg-size types]}]
  {:message-count msg-count
   :total-size    (format-bytes total-raw-bytes)
   :total-content (format-bytes total-content-len)
   :avg-msg-size  (format-bytes (if (pos? msg-count)
                                 (/ total-raw-bytes msg-count)
                                 0))
   :max-msg-size  (format-bytes max-msg-size)
   :min-msg-size  (format-bytes (if (= min-msg-size Long/MAX_VALUE)
                                 0
                                 min-msg-size))
   :types         types})

(defn get-summary
  [{:keys [start-time stop-time processed-count windows current-window]}]
  (let [end-time (or stop-time (Instant/now))
        duration (Duration/between start-time end-time)
        total-windows (conj windows current-window)
        runtime-secs  (.getSeconds duration)]
    {:runtime-seconds  runtime-secs
     :status           (if stop-time "stopped" "running")
     :total-messages   processed-count
     :messages-per-sec (float (/ processed-count 
                                (max 1 runtime-secs)))
     :windows-summary  (->> total-windows
                           (map window-summary)
                           (take-last 5))}))

(defn stop-analysis
  [{:keys [analysis-ch state]}]
  (when state
    (swap! state assoc :stop-time (Instant/now)))
  (when analysis-ch 
    (async/close! analysis-ch)))

(comment
  
  (def conn (firehose/connect-firehose))
  (def analysis (start-analysis conn :window-duration-seconds 30))
  
  ;; Get current stats while running
  (get-summary @(:state analysis))
  
  ;; Stop and get final stats
  (stop-analysis analysis)
  (get-summary @(:state analysis))
  
  ;; Cleanup firehose connection
  (firehose/disconnect conn)
  ,)

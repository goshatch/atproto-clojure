(ns net.gosha.atproto.firehose-analysis
  (:require
   [clojure.core.async      :as async]
   [clojure.tools.logging   :as log]
   [net.gosha.atproto.firehose :as firehose])
  (:import
   [java.time Instant Duration]))

(defn create-window [start duration]
  {:start-time      start
   :end-time        (.plus start duration)
   :message-count   0
   :total-bytes     0
   :types           {}})

(defn create-analysis-state
  [window-duration-seconds]
  (let [now      (Instant/now)
        duration (Duration/ofSeconds window-duration-seconds)]
    {:start-time      now
     :processed-count 0
     :current-window  (create-window now duration)
     :windows         []
     :window-duration duration}))

(defn update-window
  "Update window stats with a new message"
  [window message]
  (let [msg-size (count (str message))]
    (-> window
        (update :message-count inc)
        (update :total-bytes + msg-size)
        (update-in [:types (:type message)] (fnil inc 0)))))

(defn rotate-window!
  "Check if current window should be rotated and create new window if needed"
  [{:keys [current-window window-duration] :as state}]
  (let [now (Instant/now)]
    (if (.isAfter now (:end-time current-window))
      (let [new-window (create-window now window-duration)]
        (-> state
            (update :windows conj current-window)
            (assoc :current-window new-window)))
      state)))

(defn process-message!
  "Process a single message and update analysis state"
  [state message]
  (-> state
      rotate-window!
      (update :processed-count inc)
      (update :current-window update-window message)))

(defn start-analysis
  "Start analyzing messages from a firehose connection"
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

(defn get-summary
  "Generate a summary of the current analysis state"
  [{:keys [start-time processed-count windows current-window]}]
  (let [duration       (Duration/between start-time (Instant/now))
        total-windows  (conj windows current-window)]
    {:runtime-seconds  (.getSeconds duration)
     :total-messages   processed-count
     :messages-per-sec (float (/ processed-count 
                                (max 1 (.getSeconds duration))))
     :windows-summary  (->> total-windows
                           (map #(select-keys % [:message-count :types]))
                           (take-last 5))}))

(defn stop-analysis
  [{:keys [analysis-ch]}]
  (async/close! analysis-ch))

(comment
  ;; Example usage
  (def conn (firehose/connect-firehose))
  (def analysis (start-analysis conn :window-duration-seconds 30))
  
  ;; Get current stats after running for a while
  (get-summary @(:state analysis))
  
  ;; Cleanup
  (stop-analysis analysis)
  (firehose/disconnect conn)
  ,)

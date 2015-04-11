(ns onyx.log.zookeeper
  (:require [clojure.core.async :refer [chan >!! <!! close! thread]]
            [clojure.data.fressian :as fressian]
            [com.stuartsierra.component :as component]
            [taoensso.timbre :refer [fatal warn]]
            [zookeeper :as zk]
            [onyx.extensions :as extensions])
  (:import [org.apache.curator.test TestingServer]))

(def root-path "/onyx")

(defn prefix-path [prefix]
  (str root-path "/" prefix))

(defn build-route-paths
  "Accepts a seq of names that both define a prefixed endpoint and a function named (name)-path that returns that value"
  [paths]
  (doseq [path paths]
    (intern *ns* (symbol (str path "-path"))
            (fn [prefix] (str (prefix-path prefix) "/" path)))))

(build-route-paths '(pulse log catalog workflow flow task sentinel origin job-scheduler))

(defn serialize [x]
  (.array (fressian/write x)))

(defn deserialize [x]
  (fressian/read x))

(defn initialize-origin! [conn config prefix]
  (let [node (str (origin-path prefix) "/origin")
        bytes (serialize {:message-id -1 :replica {}})]
    (zk/create conn node :data bytes :persistent? true)))

(defrecord ZooKeeper [config]
  component/Lifecycle

  (start [component]
    (taoensso.timbre/info "Starting ZooKeeper")
    (let [onyx-id (:onyx/id config)
          server (when (:zookeeper/server? config) (TestingServer. (:zookeeper.server/port config)))
          conn (zk/connect (:zookeeper/address config))]
      (zk/create conn root-path :persistent? true)
      (zk/create conn (prefix-path onyx-id) :persistent? true)
      (zk/create conn (pulse-path onyx-id) :persistent? true)
      (zk/create conn (log-path onyx-id) :persistent? true)
      (zk/create conn (catalog-path onyx-id) :persistent? true)
      (zk/create conn (workflow-path onyx-id) :persistent? true)
      (zk/create conn (flow-path onyx-id) :persistent? true)
      (zk/create conn (task-path onyx-id) :persistent? true)
      (zk/create conn (sentinel-path onyx-id) :persistent? true)
      (zk/create conn (origin-path onyx-id) :persistent? true)
      (zk/create conn (job-scheduler-path onyx-id) :persistent? true)

      (initialize-origin! conn config onyx-id)
      (assoc component :server server :conn conn :prefix onyx-id)))

  (stop [component]
    (taoensso.timbre/info "Stopping ZooKeeper")
    (zk/close (:conn component))

    (when (:server component)
      (.stop (:server component)))

    component))

(defn zookeeper [config]
  (map->ZooKeeper {:config config}))

(defn pad-sequential-id
  "ZooKeeper sequential IDs are at least 10 digits.
   If this node's id is less, pad it. Otherwise returns the str'ed id"
  [id]
  (let [padding 10
        id-len (count (str id))]
    (if (< id-len padding)
      (str (apply str (take (- padding id-len) (repeat "0"))) id)
      (str id))))

(defmethod extensions/write-log-entry ZooKeeper
  [{:keys [conn opts prefix] :as log} data]
  (let [node (str (log-path prefix) "/entry-")
        bytes (serialize data)]
    (zk/create conn node :data bytes :persistent? true :sequential? true)))

(defmethod extensions/read-log-entry ZooKeeper
  [{:keys [conn opts prefix] :as log} position]
  (let [node (str (log-path prefix) "/entry-" (pad-sequential-id position))
        data (zk/data conn node)
        content (deserialize (:data data))]
    (assoc content :message-id position :created-at (:ctime (:stat data)))))

(defmethod extensions/register-pulse ZooKeeper
  [{:keys [conn opts prefix] :as log} id]
  (let [node (str (pulse-path prefix) "/" id)]
    (zk/create conn node :persistent? false)))

(defmethod extensions/on-delete ZooKeeper
  [{:keys [conn opts prefix] :as log} id ch]
  (let [f (fn [event]
            (when (= (:event-type event) :NodeDeleted)
              (>!! ch true)))]
    (try
      (when-not (zk/exists conn (str (pulse-path prefix) "/" id) :watcher f)
        (>!! ch true))
      (catch Exception e
        ;; Node doesn't exist.
        (>!! ch true)))))

(defn find-job-scheduler [log]
  (loop []
    (if-let [chunk
             (try
               (extensions/read-chunk log :job-scheduler nil)
               (catch Exception e
                 (warn e)
                 (warn "Job scheduler couldn't be discovered. Backing off 500ms and trying again...")
                 nil))]
      (:job-scheduler chunk)
      (do (Thread/sleep 500)
          (recur)))))

(defmethod extensions/subscribe-to-log ZooKeeper
  [{:keys [conn opts prefix] :as log} ch]
  (let [rets (chan)]
    (thread
     (try
       (let [job-scheduler (find-job-scheduler log)
             origin (extensions/read-chunk log :origin nil)
             starting-position (inc (:message-id origin))]
         (>!! rets (assoc (:replica origin) :job-scheduler job-scheduler))
         (close! rets)
         (loop [position starting-position]
           (let [path (str (log-path prefix) "/entry-" (pad-sequential-id position))]
             (if (zk/exists conn path)
               (>!! ch position)
               (loop []
                 (let [read-ch (chan 2)]
                   (zk/children conn (log-path prefix) :watcher (fn [_] (>!! read-ch true)))
                   ;; Log entry may have been added in between initial check and when we
                   ;; added the watch.
                   (when (zk/exists conn path)
                     (>!! read-ch true))
                   (<!! read-ch)
                   (close! read-ch)
                   ;; Requires one more check. Watch may have been triggered by a delete
                   ;; from a GC call.
                   (if (zk/exists conn path)
                     (>!! ch position)
                     (recur)))))
             (recur (inc position)))))
       (catch org.apache.zookeeper.KeeperException$ConnectionLossException e
         ;; ZooKeeper has been shutdown, close the subscriber cleanly.
         (close! ch))
       (catch org.apache.zookeeper.KeeperException$SessionExpiredException e
         (close! ch))
       (catch Exception e
         (fatal e))))
    (<!! rets)))

(defmethod extensions/write-chunk [ZooKeeper :catalog]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (catalog-path prefix) "/" id)
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/write-chunk [ZooKeeper :workflow]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (workflow-path prefix) "/" id)
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/write-chunk [ZooKeeper :flow-conditions]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (flow-path prefix) "/" id)
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/write-chunk [ZooKeeper :task]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (task-path prefix) "/" (:id chunk))
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/write-chunk [ZooKeeper :sentinel]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (sentinel-path prefix) "/" id)
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/write-chunk [ZooKeeper :job-scheduler]
  [{:keys [conn opts prefix] :as log} kw chunk id]
  (let [node (str (job-scheduler-path prefix) "/scheduler")
        bytes (serialize chunk)]
    (zk/create conn node :persistent? true :data bytes)))

(defmethod extensions/read-chunk [ZooKeeper :catalog]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (catalog-path prefix) "/" id)]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :workflow]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (workflow-path prefix) "/" id)]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :flow-conditions]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (flow-path prefix) "/" id)]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :task]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (task-path prefix) "/" id)]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :sentinel]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (sentinel-path prefix) "/" id)]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :origin]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (origin-path prefix) "/origin")]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/read-chunk [ZooKeeper :job-scheduler]
  [{:keys [conn opts prefix] :as log} kw id]
  (let [node (str (job-scheduler-path prefix) "/scheduler")]
    (deserialize (:data (zk/data conn node)))))

(defmethod extensions/update-origin! ZooKeeper
  [{:keys [conn opts prefix] :as log} replica message-id]
  (let [node (str (origin-path prefix) "/origin")
        version (:version (zk/exists conn node))
        content (deserialize (:data (zk/data conn node)))]
    (when (< (:message-id content) message-id)
      (let [new-content {:message-id message-id :replica replica}]
        (zk/set-data conn node (serialize new-content) version)))))

(defmethod extensions/gc-log-entry ZooKeeper
  [{:keys [conn opts prefix] :as log} position]
  (let [node (str (log-path prefix) "/entry-" (pad-sequential-id position))]
    (zk/delete conn node)))


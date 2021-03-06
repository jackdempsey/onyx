(ns onyx.peer.multi-input-test
  (:require [com.stuartsierra.component :as component]
            [onyx.system :refer [onyx-development-env]]
            [midje.sweet :refer :all]
            [onyx.queue.hornetq-utils :as hq-util]
            [onyx.api]))

(def id (java.util.UUID/randomUUID))

(def config (read-string (slurp (clojure.java.io/resource "test-config.edn"))))

(def scheduler :onyx.job-scheduler/round-robin)

(def env-config
  {:hornetq/mode :udp
   :hornetq/server? true
   :hornetq.server/type :embedded
   :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
   :hornetq.udp/group-address (:group-address (:hornetq config))
   :hornetq.udp/group-port (:group-port (:hornetq config))
   :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
   :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
   :hornetq.embedded/config (:configs (:hornetq config))
   :zookeeper/address (:address (:zookeeper config))
   :zookeeper/server? true
   :zookeeper.server/port (:spawn-port (:zookeeper config))
   :onyx/id id
   :onyx.peer/job-scheduler scheduler})

(def peer-config
  {:hornetq/mode :udp
   :hornetq.udp/cluster-name (:cluster-name (:hornetq config))
   :hornetq.udp/group-address (:group-address (:hornetq config))
   :hornetq.udp/group-port (:group-port (:hornetq config))
   :hornetq.udp/refresh-timeout (:refresh-timeout (:hornetq config))
   :hornetq.udp/discovery-timeout (:discovery-timeout (:hornetq config))
   :zookeeper/address (:address (:zookeeper config))
   :onyx/id id
   :onyx.peer/inbox-capacity (:inbox-capacity (:peer config))
   :onyx.peer/outbox-capacity (:outbox-capacity (:peer config))
   :onyx.peer/job-scheduler scheduler})

(def env (onyx.api/start-env env-config))

(def n-messages 15000)

(def batch-size 1320)

(def k-inputs 4)

(def echo 1000)

(def in-queues (map (fn [_] (str (java.util.UUID/randomUUID))) (range k-inputs)))

(def out-queue (str (java.util.UUID/randomUUID)))

(def hq-config {"host" (:host (:non-clustered (:hornetq config)))
                "port" (:port (:non-clustered (:hornetq config)))})

(doseq [in-queue in-queues]
  (hq-util/create-queue! hq-config in-queue))

(hq-util/create-queue! hq-config out-queue)

(def messages
  (->> k-inputs
       (inc)
       (range)
       (map (fn [k] (* k (/ n-messages k-inputs))))
       (partition 2 1)
       (map (partial apply range))
       (map (fn [r] (map (fn [x] {:n x}) r)))))

(doseq [[q b] (map (fn [q b] [q b]) in-queues messages)]
  (hq-util/write-and-cap! hq-config q b echo))

(defn my-inc [{:keys [n] :as segment}]
  (assoc segment :n (inc n)))

(def input-entries
  (map
   (fn [k]
     {:onyx/name (keyword (str "in-" k))
      :onyx/ident :hornetq/read-segments
      :onyx/type :input
      :onyx/medium :hornetq
      :onyx/consumption :concurrent
      :hornetq/queue-name (nth in-queues (dec k))
      :hornetq/host (:host (:non-clustered (:hornetq config)))
      :hornetq/port (:port (:non-clustered (:hornetq config)))
      :onyx/batch-size batch-size})
   (range 1 (inc k-inputs))))

(def catalog
  (concat
   input-entries
   [{:onyx/name :inc
     :onyx/fn :onyx.peer.multi-input-test/my-inc
     :onyx/type :function
     :onyx/consumption :concurrent
     :onyx/batch-size batch-size}

    {:onyx/name :out
     :onyx/ident :hornetq/write-segments
     :onyx/type :output
     :onyx/medium :hornetq
     :onyx/consumption :concurrent
     :hornetq/queue-name out-queue
     :hornetq/host (:host (:non-clustered (:hornetq config)))
     :hornetq/port (:port (:non-clustered (:hornetq config)))
     :onyx/batch-size batch-size}]))

(def workflow
  (vec
   (concat
    [[:inc :out]]
    (map (fn [a] [(keyword (str "in-" a)) :inc])
         (range 1 (inc k-inputs))))))

(def v-peers (onyx.api/start-peers! 6 peer-config))

(onyx.api/submit-job
 peer-config
 {:catalog catalog :workflow workflow
  :task-scheduler :onyx.task-scheduler/round-robin})

(def results (hq-util/consume-queue! hq-config out-queue echo))

(doseq [v-peer v-peers]
  (onyx.api/shutdown-peer v-peer))

(onyx.api/shutdown-env env)

(let [expected (set (map (fn [x] {:n (inc x)}) (range n-messages)))]
  (fact (set (butlast results)) => expected)
  (fact (last results) => :done))


(ns frontend.db.restore
  "Fns for DB restore(from text or sqlite)"
  (:require [cljs-bean.core :as bean]
            [clojure.string :as string]
            [datascript.core :as d]
            [electron.ipc :as ipc]
            [frontend.config :as config]
            [frontend.db.conn :as db-conn]
            [frontend.db.migrate :as db-migrate]
            [frontend.db.persist :as db-persist]
            [frontend.db.react :as react]
            [frontend.db.utils :as db-utils]
            [frontend.state :as state]
            [goog.object :as gobj]
            [logseq.db.default :as default-db]
            [logseq.db.schema :as db-schema]
            [promesa.core :as p]
            [frontend.util :as util]
            [cljs-time.core :as t]
            [cognitect.transit :as transit]))

(def ^:private t-reader (transit/reader :json))

(defn- old-schema?
  "Requires migration if the schema version is older than db-schema/version"
  [db]
  (let [v (db-migrate/get-schema-version db)
        ;; backward compatibility
        v (if (integer? v) v 0)]
    (cond
      (= db-schema/version v)
      false

      (< db-schema/version v)
      (do
        (js/console.error "DB schema version is newer than the app, please update the app. " ":db-version" v)
        false)

      :else
      true)))

(defn- restore-graph-from-text!
  "Swap db string into the current db status
   stored: the text to restore from"
  [repo stored]
  (p/let [db-name (db-conn/datascript-db repo)
          db-conn (d/create-conn (db-conn/get-schema repo))
          _ (swap! db-conn/conns assoc db-name db-conn)
          _ (when stored
              (let [stored-db (try (db-utils/string->db stored)
                                   (catch :default _e
                                     (js/console.warn "Invalid graph cache")
                                     (d/empty-db (db-conn/get-schema repo))))
                    attached-db (d/db-with stored-db
                                           default-db/built-in-pages) ;; TODO bug overriding uuids?
                    db (if (old-schema? attached-db)
                         (db-migrate/migrate attached-db)
                         attached-db)]
                (db-conn/reset-conn! db-conn db)))]
    (d/transact! db-conn [{:schema/version db-schema/version}])))

(defn- eav->datom
  [uuid->db-id-map [e a v]]
  (let [v' (cond
             (and (= :block/uuid a) (string? v))
             (uuid v)

             (and (coll? v) (= :block/uuid (first v)) (string? (second v)))
             (get uuid->db-id-map (second v) v)

             :else
             v)]
    (d/datom e a v')))

(defn- set-unloaded-block-ids!
  [repo data]
  (util/profile
   "Set unloaded-block-ids"
   (let [unloaded-block-ids (transient #{})]
     (doseq [b data]
       (conj! unloaded-block-ids (gobj/get b "uuid") (gobj/get b "page_uuid")))
     (state/set-state! [repo :restore/unloaded-blocks] (persistent! unloaded-block-ids)))))

(defn- restore-other-data-from-sqlite!
  [repo data uuid->db-id-map]
  (let [start (util/time-ms)
        conn (db-conn/get-db repo false)
        datoms (transient (set (d/datoms @conn :eavt)))]

    (doseq [block data]
      (let [uuid (gobj/get block "uuid")
            eid (get uuid->db-id-map uuid)
            _ (when (nil? eid)
                (prn "Error: block without eid ")
                (js/console.dir block))
            _ (assert eid (str "Can't find eid " eid ", block: " block))
            avs (->> (gobj/get block "datoms")
                     (transit/read t-reader))]
        (doseq [[a v] avs]
          (when (not= :block/uuid a)
            (let [datom (eav->datom uuid->db-id-map [eid a v])]
              (conj! datoms datom))))))

    (let [all-datoms (persistent! datoms)
          new-db (util/profile
                  (str "DB init! " (count all-datoms) " datoms")
                  (d/init-db all-datoms db-schema/schema-for-db-based-graph))]

      (reset! conn new-db)

      (let [end (util/time-ms)]
        (println "[debug] load others from SQLite: " (int (- end start)) " ms."))

      (p/let [_ (p/delay 150)]          ; More time for UI refresh
        (state/set-state! [repo :restore/unloaded-blocks] nil)
        (state/set-state! [repo :restore/unloaded-pages] nil)
        (state/set-state! :graph/loading? false)
        (state/pub-event! [:ui/re-render-root])))))

(defn- uuid-string?
  [s]
  (and (string? s)
       (= (count s) 36)
       (string/includes? s "-")))

(defn- restore-graph-from-sqlite!
  "Load initial data from SQLite"
  [repo]
  (state/set-state! :graph/loading? true)
  (p/let [start-time (t/now)
          data (ipc/ipc :get-initial-data repo)
          {:keys [all-pages all-blocks journal-blocks init-data]} (bean/->clj data)
          uuid->db-id-tmap (transient (hash-map))
          *next-db-id (atom 100001)
          assign-id-to-uuid-fn (fn [uuid-str]
                                 (let [id @*next-db-id]
                                   (conj! uuid->db-id-tmap [uuid-str id])
                                   (swap! *next-db-id inc)
                                   id))
          pages (mapv (fn [page]
                        (let [eid (assign-id-to-uuid-fn (:uuid page))]
                          (->> page
                               :datoms
                               (transit/read t-reader)
                               (mapv (partial apply vector eid)))))
                      all-pages)
          all-blocks' (doall
                       (keep (fn [b]
                               (let [eid (assign-id-to-uuid-fn (:uuid b))]
                                 (when (and (uuid-string? (:uuid b))
                                            (uuid-string? (:page_uuid b)))
                                   [[eid :block/uuid (:uuid b)]
                                    [eid :block/page [:block/uuid (:page_uuid b)]]])))
                             all-blocks))
          init-data' (doall
                      (keep (fn [b]
                              (let [eid (assign-id-to-uuid-fn (:uuid b))]
                                (if (and (uuid-string? (:uuid b))
                                         (not (contains?  #{3 6} (:type b)))) ; deleted blocks still refed
                                  [[eid :block/uuid (:uuid b)]]
                                  (->> b
                                       :datoms
                                       (transit/read t-reader)
                                       (mapv (partial apply vector eid))))))
                            init-data))
          uuid->db-id-map (persistent! uuid->db-id-tmap)
          journal-blocks' (mapv
                           (fn [b]
                             (let [eid (get uuid->db-id-map (:uuid b))]
                               (->> b
                                    :datoms
                                    (transit/read t-reader)
                                    (mapv (partial apply vector eid)))))
                           journal-blocks)
          pages-eav-coll (apply concat pages)
          blocks-eav-colls (->> (concat all-blocks' journal-blocks' init-data')
                                (apply concat))
          all-eav-coll (doall (concat pages-eav-coll blocks-eav-colls))
          datoms (map
                   (partial eav->datom uuid->db-id-map)
                   all-eav-coll)
          db-name (db-conn/datascript-db repo)
          db-conn (util/profile :restore-graph-from-sqlite!-init-db
                                (d/conn-from-datoms datoms db-schema/schema-for-db-based-graph))
          _ (swap! db-conn/conns assoc db-name db-conn)
          end-time (t/now)]
    (println :restore-graph-from-sqlite!-prepare (t/in-millis (t/interval start-time end-time)) "ms"
             " Datoms in total: " (count datoms))

    ;; TODO: Store schema in sqlite
    ;; (db-migrate/migrate attached-db)

    (d/transact! db-conn [(react/kv :db/type "db")
                          {:schema/version db-schema/version}]
                 {:skip-persist? true})

    (js/setTimeout
     (fn []
       (p/let [other-data (ipc/ipc :get-other-data repo (map :uuid journal-blocks))
               _ (set-unloaded-block-ids! repo other-data)
               _ (p/delay 10)]
         (restore-other-data-from-sqlite! repo other-data uuid->db-id-map)))
     100)))

(defn restore-graph!
  "Restore db from serialized db cache"
  [repo]
  (if (string/starts-with? repo config/db-version-prefix)
    (restore-graph-from-sqlite! repo)
    (p/let [db-name (db-conn/datascript-db repo)
            stored (db-persist/get-serialized-graph db-name)]
      (restore-graph-from-text! repo stored))))
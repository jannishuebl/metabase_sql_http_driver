(ns metabase.driver.sql-http
  (:require
    [metabase.driver.sql-http.type-coercion :as type]
    [clojure.string :as str]
    [java-time.api :as t]
    [medley.core :as m]
    [metabase.driver :as driver]
    [metabase.driver.sql.query-processor :as sql.qp] 
    [metabase.query-processor.store :as qp.store]
    [metabase.lib.metadata :as lib.metadata]
    [metabase.util.honey-sql-2 :as h2x]
    [metabase.util.log :as log]
    [cheshire.core :as json]
    [clojure.data.json :as json2]
    [clojure.walk :as walk]
    [clj-http.client :as client])

  (:import
    (java.sql
      Connection
      PreparedStatement
      ResultSet
      ResultSetMetaData
      Statement
      Time
      Types)
    (com.jayway.jsonpath JsonPath Predicate)
    (java.time LocalDate LocalTime OffsetTime)
    (java.time.temporal ChronoField)))

(set! *warn-on-reflection* true)

(driver/register! :sql-http, :parent :sql)

(doseq [[feature supported?] {:metadata/key-constraints      false  ;; fetching metadata about foreign key constraints is not supported, but JOINs generally are.
                              :upload-with-auto-pk           false
                              :datetime-diff                 true}]
  (defmethod driver/database-supports? [:sql-http feature] [_driver _feature _db] supported?))


(declare compile-expression compile-function)

(defn json-path
  [query body]
  (JsonPath/read body query (into-array Predicate [])))

(defn compile-function
  [[operator & arguments]]
  (case (keyword operator)
    :count count
    :sum   #(reduce + (map (compile-expression (first arguments)) %))
    :float #(Float/parseFloat ((compile-expression (first arguments)) %))
    (throw (Exception. (str "Unknown operator: " operator)))))

(defn compile-expression
  [expr]
  (cond
    (string? expr)  (partial json-path expr)
    (number? expr)  (constantly expr)
    (vector? expr)  (compile-function expr)
    :else           (throw (Exception. (str "Unknown expression: " expr)))))

(defn aggregate
  [rows metrics breakouts]
  (let [breakouts-fns (map compile-expression breakouts)
        breakout-fn   (fn [row] (for [breakout breakouts-fns] (breakout row)))
        metrics-fns   (map compile-expression metrics)]
    (for [[breakout-key breakout-rows] (group-by breakout-fn rows)]
      (concat breakout-key (for [metrics-fn metrics-fns]
                             (metrics-fn breakout-rows))))))

(defn extract-fields
  [rows fields]
  (let [fields-fns (map compile-expression fields)]
    (for [row rows]
      (for [field-fn fields-fns]
        (field-fn row)))))

(defn field-names
  [fields]
  (vec (for [field fields]
         (if (string? field)
           {:name field}
           {:name (json/generate-string field)}))))

(defn api-query [query rows respond]
  (let [fields        (or (:fields (:result query)) (keys (first rows)))
        aggregations  (or (:aggregation (:result query)) [])
        breakouts     (or (:breakout (:result query)) [])
        raw           (and (= (count breakouts) 0) (= (count aggregations) 0))
        columns       (if raw
                        (field-names fields)
                        (field-names (concat breakouts aggregations)))
        result         (if raw
                         (extract-fields rows fields)
                         (aggregate rows aggregations breakouts))]
    (respond {:cols columns}
             result)))

(defn database->config
  [database]
  {
   :url (:url (:details database))
   :method (:method (:details database))
   :headers (json/parse-string (:custom_headers (:details database)) keyword)
   }
  )


(defn execute-http-request [native-query respond query]
  (println "native-query" native-query)
  (let [body          (json2/write-str {:sql (:query native-query) :params (:params native-query)}) 
        headers       (:headers query)
        _             (println "headers" (pr-str headers))
        url           (:url query)
        url-conn      (.openConnection (java.net.URL. url))
        _             (.setRequestMethod url-conn (or (:method query) "GET"))
        _             (when headers (doseq [[k v] headers]
                                      (.setRequestProperty url-conn (name k) v)))
        _             (.setDoOutput url-conn true)
        _             (when body (with-open [out-writer (java.io.OutputStreamWriter. (.getOutputStream url-conn))]
                                   (.write out-writer body)))
        response      (with-open [in-reader (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream url-conn)))]
                        (doall (map json2/read-str (line-seq in-reader))))
        rows-path     (or (:path (:result query)) "$")
        rows-raw      (json-path rows-path (walk/stringify-keys response))
        rows          (type/auto-convert-types rows-raw)]
    (api-query query rows respond)))


(defmethod driver/execute-reducible-query :sql-http
  [driver {query :native} context respond]
  (execute-http-request query respond (database->config (lib.metadata/database (qp.store/metadata-provider))) )
  )

(defmethod driver/describe-database :sql-http
  [_driver _database]
  nil )


(defmethod driver/describe-table :sql-http
  [driver database {table_name :name, schema :schema}]
  nil )

(defmethod driver/describe-table-fks :sql-http
  [_ _ _]
  nil)


(defmethod driver/can-connect? :sql-http
  [driver details]
  true )

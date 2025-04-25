(ns metabase.driver.duckdb-http
  (:require
   [metabase.driver.sql-http.type-coercion :as type]
   [metabase.driver.sql-http :as sql-http]
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

(driver/register! :duckdb-http, :parent :sql-http)

(doseq [[feature supported?] {:metadata/key-constraints      false  ;; fetching metadata about foreign key constraints is not supported, but JOINs generally are.
                              :upload-with-auto-pk           false
                              :datetime-diff                 true}]
  (defmethod driver/database-supports? [:duckdb-http feature] [_driver _feature _db] supported?))


(defn pattern-based-database-type->base-type
  "Return a `database-type->base-type` function that matches types based on a sequence of pattern / base-type pairs.
  `pattern->type` is a map of regex pattern to MBQL type keyword."
  [pattern->type]
  (fn database-type->base-type [column-type]
    (let [column-type (name column-type)]
      (some
       (fn [[pattern base-type]]
         (when (re-find pattern column-type)
           base-type))
       pattern->type))))


(def ^:private database-type->base-type
  (pattern-based-database-type->base-type
   [[#"BOOLEAN"                  :type/Boolean]
    [#"BOOL"                     :type/Boolean]
    [#"LOGICAL"                  :type/Boolean]
    [#"HUGEINT"                  :type/BigInteger]
    [#"UBIGINT"                  :type/BigInteger]
    [#"BIGINT"                   :type/BigInteger]
    [#"INT8"                     :type/BigInteger]
    [#"LONG"                     :type/BigInteger]
    [#"INT4"                     :type/Integer]
    [#"SIGNED"                   :type/Integer]
    [#"INT2"                     :type/Integer]
    [#"SHORT"                    :type/Integer]
    [#"INT1"                     :type/Integer]
    [#"UINTEGER"                 :type/Integer]
    [#"USMALLINT"                :type/Integer]
    [#"UTINYINT"                 :type/Integer]
    [#"INTEGER"                  :type/Integer]
    [#"SMALLINT"                 :type/Integer]
    [#"TINYINT"                  :type/Integer]
    [#"INT"                      :type/Integer]
    [#"DECIMAL"                  :type/Decimal]
    [#"DOUBLE"                   :type/Float]
    [#"FLOAT8"                   :type/Float]
    [#"NUMERIC"                  :type/Float]
    [#"REAL"                     :type/Float]
    [#"FLOAT4"                   :type/Float]
    [#"FLOAT"                    :type/Float]
    [#"VARCHAR"                  :type/Text]
    [#"BPCHAR"                   :type/Text]
    [#"CHAR"                     :type/Text]
    [#"TEXT"                     :type/Text]
    [#"STRING"                   :type/Text]
    [#"JSON"                     :type/JSON]
    [#"BLOB"                     :type/*]
    [#"BYTEA"                    :type/*]
    [#"VARBINARY"                :type/*]
    [#"BINARY"                   :type/*]
    [#"UUID"                     :type/UUID]
    [#"TIMESTAMPTZ"              :type/DateTimeWithTZ]
    [#"TIMESTAMP WITH TIME ZONE" :type/DateTimeWithTZ]
    [#"DATETIME"                 :type/DateTime]
    [#"TIMESTAMP_S"              :type/DateTime]
    [#"TIMESTAMP_MS"             :type/DateTime]
    [#"TIMESTAMP_NS"             :type/DateTime]
    [#"TIMESTAMP"                :type/DateTime]
    [#"DATE"                     :type/Date]
    [#"TIME"                     :type/Time]
    [#"GEOMETRY"                 :type/*]]))

;; date processing for aggregation
(defmethod driver/db-start-of-week :duckdb-http [_] :monday)

(defmethod sql.qp/add-interval-honeysql-form :duckdb-http
  [driver hsql-form amount unit]
  (if (= unit :quarter)
    (recur driver hsql-form (* amount 3) :month)
    (h2x/+ (h2x/->timestamp-with-time-zone hsql-form) [:raw (format "(INTERVAL '%d' %s)" (int amount) (name unit))])))

(defmethod sql.qp/date [:duckdb-http :default]         [_ _ expr] expr)
(defmethod sql.qp/date [:duckdb-http :minute]          [_ _ expr] [:date_trunc (h2x/literal :minute) expr])
(defmethod sql.qp/date [:duckdb-http :minute-of-hour]  [_ _ expr] [:minute expr])
(defmethod sql.qp/date [:duckdb-http :hour]            [_ _ expr] [:date_trunc (h2x/literal :hour) expr])
(defmethod sql.qp/date [:duckdb-http :hour-of-day]     [_ _ expr] [:hour expr])
(defmethod sql.qp/date [:duckdb-http :day]             [_ _ expr] [:date_trunc (h2x/literal :day) expr])
(defmethod sql.qp/date [:duckdb-http :day-of-month]    [_ _ expr] [:day expr])
(defmethod sql.qp/date [:duckdb-http :day-of-year]     [_ _ expr] [:dayofyear expr])

(defmethod sql.qp/date [:duckdb-http :day-of-week]
  [driver _ expr]
  (sql.qp/adjust-day-of-week driver [:isodow expr]))

(defmethod sql.qp/date [:duckdb-http :week]
  [driver _ expr]
  (sql.qp/adjust-start-of-week driver (partial conj [:date_trunc] (h2x/literal :week)) expr))

(defmethod sql.qp/date [:duckdb-http :month]           [_ _ expr] [:date_trunc (h2x/literal :month) expr])
(defmethod sql.qp/date [:duckdb-http :month-of-year]   [_ _ expr] [:month expr])
(defmethod sql.qp/date [:duckdb-http :quarter]         [_ _ expr] [:date_trunc (h2x/literal :quarter) expr])
(defmethod sql.qp/date [:duckdb-http :quarter-of-year] [_ _ expr] [:quarter expr])
(defmethod sql.qp/date [:duckdb-http :year]            [_ _ expr] [:date_trunc (h2x/literal :year) expr])

(defmethod sql.qp/datetime-diff [:duckdb-http :year]
  [_driver _unit x y]
  [:datesub (h2x/literal :year) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb-http :quarter]
  [_driver _unit x y]
  [:datesub (h2x/literal :quarter) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb-http :month]
  [_driver _unit x y]
  [:datesub (h2x/literal :month) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb-http :week]
  [_driver _unit x y]
  (h2x// [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)] 7))

(defmethod sql.qp/datetime-diff [:duckdb-http :day]
  [_driver _unit x y]
  [:datesub (h2x/literal :day) (h2x/cast "date" x) (h2x/cast "date" y)])

(defmethod sql.qp/datetime-diff [:duckdb-http :hour]
  [_driver _unit x y]
  [:datesub (h2x/literal :hour) x y])

(defmethod sql.qp/datetime-diff [:duckdb-http :minute]
  [_driver _unit x y]
  [:datesub (h2x/literal :minute) x y])

(defmethod sql.qp/datetime-diff [:duckdb-http :second]
  [_driver _unit x y]
  [:datesub (h2x/literal :second) x y])

(defmethod sql.qp/unix-timestamp->honeysql [:duckdb-http :seconds]
  [_ _ expr]
  [:to_timestamp (h2x/cast :DOUBLE expr)])

(defmethod sql.qp/->honeysql [:duckdb-http :regex-match-first]
  [driver [_ arg pattern]]
  [:regexp_extract (sql.qp/->honeysql driver arg) (sql.qp/->honeysql driver pattern)])


(defn database->config
  [database]
  (let [headers (merge
                  {"Content-Type" "application/json"
                   "Accept" "application/json"}
                  (json/parse-string (:custom_headers (:details database)) keyword))]
    {
     :url (:url (:details database))
     :method (:method (:details database))
     :headers headers
     }))

(defmethod driver/execute-reducible-query :duckdb-http
  [driver {query :native} context respond]
  (sql-http/execute-http-request query respond (database->config (lib.metadata/database (qp.store/metadata-provider))) )
  )

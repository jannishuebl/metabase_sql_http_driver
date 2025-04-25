(ns metabase.driver.sql-http.type-coercion
  (:import [java.time LocalDate LocalDateTime OffsetDateTime ZonedDateTime Instant]
           [java.time.format DateTimeFormatter DateTimeParseException]))

(defn try-parse [parse-fn s]
  (try
    (parse-fn s)
    (catch DateTimeParseException _ nil)))

(defn coerce-string [s]
  (or
    (try-parse #(LocalDate/parse % DateTimeFormatter/ISO_LOCAL_DATE) s)
    (try-parse #(LocalDateTime/parse % DateTimeFormatter/ISO_LOCAL_DATE_TIME) s)
    (try-parse #(OffsetDateTime/parse % DateTimeFormatter/ISO_OFFSET_DATE_TIME) s)
    (try-parse #(ZonedDateTime/parse % DateTimeFormatter/ISO_ZONED_DATE_TIME) s)
    (try-parse #(Instant/parse %) s)
    s))

(defn auto-convert-types [rows]
  (mapv
    (fn [row]
      (into {}
            (map (fn [[k v]]
                   [k (if (string? v) (coerce-string v) v)])
                 row)))
    rows))


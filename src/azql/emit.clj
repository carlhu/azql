(ns azql.emit
  (:use [azql util dialect])
  (:require [clojure.string :as s])
  (:use clojure.template)
  (:require [clojure.java.jdbc :as jdbc]))

(def ^:dynamic *dialect-naming-strategy-installed* false)

(defn-dialect naming-strategy
  "Returns naming strategy for clojure/jdbc."
  []
  {:entity (fn [x] (str \" x \"))
   :keyword s/lower-case})

(defmacro with-dialect-namind-strategy
  "Registers register AZQL naming strategy in `clojure/jdbc.`"
  [& body]
  `(let [f# (fn [] ~@body)]
     (if *dialect-naming-strategy-installed*
       (f#)
       (binding [*dialect-naming-strategy-installed* true]
         (jdbc/with-naming-strategy (naming-strategy) (f#))))))

(defprotocol SqlLike
  (as-sql [this] "Converts object to 'Sql'."))

(defrecord Sql [sql args]
  SqlLike
  (as-sql [this] this))

(defn sql?
  "Checks if expression is rendered (raw) SQL."
  [s]
  (instance? Sql s))

(defn raw
  "Constructs new raw SQL (without parameters)."
  ([text] (Sql. (str text) nil)))

(defn arg
  "Constructs new parameter."
  [value]
  (Sql. "?" [value]))

(defn batch-arg
  "Construct new batch parameter."
  [values]
  (Sql. "?" [(with-meta values {:batch true})]))

(defn parse-qname
  "Splits qualified name and return first part. Ex :a.val => [:a :val]."
  [qname]
  (let [n (name qname)
        rp (s/split n #"\.")]
    (mapv keyword rp)))

(defn qualifier
  "Returns first part of qualified name. Ex: :a.val => :a, :x => nil."
  [qname]
  (when qname
    (let [n (parse-qname qname)]
      (when (> (count n) 1)
        (first n)))))

(defn quote-name
  "Quotes name. Doesn't split on '.'."
  [s]
  (with-dialect-namind-strategy
    (let [s (str s)]
      (if (= s "*") s (#'jdbc/*as-str* s)))))

(defn emit-qname
  "Parses and escapes qualified name. Ex :a.val => \"a\".\"val\"."
  [qname]
  (with-dialect-namind-strategy
    (jdbc/as-str quote-name qname)))

(defn qname
  "Constructs qualified name."
  ([qn] (Sql. (emit-qname qn) nil))
  ([qn alias] (Sql. (emit-qname [qn alias]) nil)))

(defn- insert-space?
  [^String a ^String b]
  (not
   (or
    (nil? a)
    (nil? b)
    (.isEmpty b)
    (.startsWith b ")")
    (.endsWith a "(")
    (.startsWith b ","))))

(defn- join-sql-strings
  [strings]
  (let [sb (StringBuilder.)]
    (reduce (fn [prev curr]
              (when (and (not (nil? prev)) (insert-space? prev curr))
                (.append sb \space))
              (.append sb curr)
              curr) nil strings)
    (str sb)))

(extend-protocol SqlLike
  clojure.lang.Sequential
  (as-sql [this]
    (if (:batch (meta this))
      (batch-arg this)
      (let [s (map as-sql (eager-filtered-flatten this #(not (:batch (meta %)))))]
        (Sql. (join-sql-strings (map :sql s))
              (mapcat :args s)))))
  clojure.lang.Keyword
  (as-sql [this] (qname this))
  clojure.lang.Symbol
  (as-sql [this] (raw (name this)))
  Object
  (as-sql [this] (arg this))
  nil
  (as-sql [this] (arg nil)))

(def NONE (raw ""))

(defn sql*
  "Converts object to Sql. Doesn't install azql context.
   You should prefer azql.core/sql."
  ([] NONE)
  ([v]
    (if (sql? v)
      v
      (let [v (as-sql v)]
        (assoc v :args (vec (:args v))))))
([v & r] (sql* (cons v r))))

(do-template
 [kname] (def kname (raw (str (s/replace (name 'kname) #"_" " "))))

 SELECT, FROM, WHERE, JOIN, IN, NOT_IN, ON, AND, OR, NOT, NULL, AS,
 IS_NULL, IS_NOT_NULL, ORDER_BY, GROUP_BY, HAVING, DESC, ASC, SET,
 COUNT, MIN, MAX, AVG, SUM, INSERT, DELETE, VALUES, INTO, UPDATE,
 LEFT_OUTER_JOIN, RIGHT_OUTER_JOIN, FULL_OUTER_JOIN,
 CROSS_JOIN, INNER_JOIN, DISTINCT, ALL,LIMIT, OFFSET,
 EXISTS, NOT_EXISTS, ALL, ANY, SOME,
 LIKE, ESCAPE)

(do-template
 [kname value] (def kname (raw value))

 COMMA ",",ASTERISK "*", QMARK "?", LP "(", RP ")",
 EQUALS "=", NOT_EQUALS "<>", LESS "<", GREATER ">",
 LESS_EQUAL "<=", GREATER_EQUAL ">=", UPLUS "+",
 PLUS "+", MINUS "-", UMINUS "-", DIVIDE "/", MULTIPLY "*")

(defn parenthesis
  "Surrounds expression with parenthesis."
  [e]
  ^{::orig e} [LP e RP])

(defn remove-parenthesis
  "Removes parenthesis."
  [e]
  (or (::orig (meta e)) e))

(defn comma-list
  "Returns values separated by comma."
  [values]
  (interpose COMMA (map remove-parenthesis values)))

(defn as-alias
  "Interprets value as column/table alias"
  [n]
  (check-argument (or (keyword? n) (string? n))
                  "Invalid alias, extected keyword or string.")
  (keyword (name n)))

(ns metaclj.impl.parse)

;TODO: namespaced symbols can't be shadowed; hence eclj.env/patches
;TODO: validation conditions.
; ie raise error for (var inc inc), (quote x x), odd number bindings, etc

(defprotocol Form
  (-parse [expr env]))

(defrecord Syntax [forms env]
  Form
  (-parse [x env]
    x))

(defn syntax? [x]
  (instance? Syntax x))

(defn head [x]
  (if (syntax? x)
    :syntax
    (:head x)))

(defn parse [x env]
  (-parse x env))

(defn parse-meta [form env]
  (when-let [metadata (meta form)]
    {:head :meta :form form :env env
     :expr (with-meta form nil) :meta metadata}))

(defn parse-constant [x env]
  (or (parse-meta x env)
      {:head :constant :form x :env env :value x}))

(defn parse-collection [coll env]
  (or (parse-meta coll env)
      {:head :collection :form coll :env env :coll coll}))

(doseq [t [nil java.lang.Object]]
  (extend t Form {:-parse parse-constant}))

(defmulti parse-seq (fn [xs env] (first xs)))

(defn parse-invoke [[f & args :as form] env]
  {:head :invoke :form form :env env :f f :args args})

(extend-protocol Form

  clojure.lang.Var
  (-parse [v env]
    (let [{:keys [name ns]} (meta v)
          sym (symbol (str (ns-name ns)) (clojure.core/name name))]
      (parse-seq (list 'var sym) env)))

  clojure.lang.Symbol
  (-parse [sym env]
    {:head :name :form sym :env env :sym sym})

  clojure.lang.ISeq
  (-parse [xs env]
    (cond
      (empty? xs) (parse-constant xs env)
      (symbol? (first xs)) (parse-seq xs env)
      :else (parse-invoke xs env)))

  clojure.lang.AMapEntry
  (-parse [kvp env]
    (parse-collection (vec kvp) env))

)

(doseq [t [clojure.lang.PersistentArrayMap
           clojure.lang.PersistentHashMap
           clojure.lang.PersistentHashSet
           clojure.lang.PersistentQueue
           clojure.lang.PersistentTreeMap
           clojure.lang.PersistentTreeSet
           clojure.lang.PersistentVector]]
  (extend t Form {:-parse parse-collection}))

(defn expand-dot [[head & tail :as form] env]
  (let [s (str head)]
    (cond
      (= s "..") nil ;XXX Special cases the `..` macro, but could be others.
      (.endsWith s ".") (let [class (symbol (apply str (butlast s)))]
                          {:head :new :form form :env env
                           :class class :args (vec tail)})
      (.startsWith s ".") (let [member (symbol (apply str (next s)))
                                [obj & args] tail]
                            {:head :interop :form form :env env
                             :target obj :member member :args (vec args)}))))

(defmethod parse-seq :default
  [form env]
  (or (and (symbol? (first form)) (expand-dot form env))
      {:head :invoke :form form :env env
       :f (first form) :args (vec (rest form))}))

(defmethod parse-seq 'if
  [[_ test then else :as form] env]
  {:head :if :form form :env env
   :test test :then then :else else})

(defmethod parse-seq 'var
  [[_ sym :as form] env]
  {:head :var :form form :env env :sym sym})

(defmethod parse-seq 'do
  [[_ & body :as form] env]
  (if (seq body)
    (let [v (vec body)]
      {:head :do :form form :env env
       :statements (pop v) :ret (peek v)})
    {:head :constant :form form :env env :value nil}))

(defmethod parse-seq 'quote
  [[_ value :as form] env]
  {:head :constant :form form :env env :value value})

(defn implicit-do [body]
  (case (count (take 2 body))
    0 nil
    1 (first body)
    (list* 'do body)))

(defmethod parse-seq 'let*
  [[_ bindings & body :as form] env]
  {:head :let :form form :env env
   :bindings (mapv (fn [[name init]]
                     (when (namespace name)
                       (throw (Exception.
                                (str "Can't let qualified name: " name))))
                     {:name name :init init})
                   (partition 2 bindings))
   :expr (implicit-do body)})

(defn parse-method [params body]
  ;;TODO: validate signature.
  (let [[fixed [_ variadic]] (split-with (complement '#{&}) params)]
    {:fixed (vec fixed)
     :variadic variadic
     :expr (implicit-do body)}))

(defn parse-fn [[_ & fn-tail] env]
  ;;TODO: validate methods.
  (let [[name impl] (if (symbol? (first fn-tail))
                      [(first fn-tail) (next fn-tail)]
                      [nil fn-tail])
        methods (for [[sig & body] (if (vector? (first impl)) [impl] impl)]
                  (parse-method sig body))]
    {:name name :env env :methods methods}))

(defmethod parse-seq 'fn*
  [form env]
  (or (parse-meta form env)
      (merge {:head :fn :form form :env env}
             (parse-fn form env))))

(defmethod parse-seq 'letfn*
  [[_ bindings & body :as form] env]
  {:head :letfn :form form :env env
   :bindings (->> (next bindings)
                  (take-nth 2)
                  (map (comp (juxt :name identity) #(parse-fn % env)))
                  vec)
   :expr (implicit-do body)})

(defmethod parse-seq 'try
  [[_ & body :as form] env]
  (let [catch? (every-pred seq? #(= (first %) 'catch))
        default? (every-pred catch? #(= (second %) :default))
        finally? (every-pred seq? #(= (first %) 'finally))]
    (loop [{:keys [state forms body] :as parser}
           {:state :start :forms body :body []
            :catches [] :default nil :finally nil}]
      (if-let [[form & forms*] forms]
        (let [parser* (assoc parser :forms forms*)]
          (case state
            :start
              (cond
                (catch? form) (recur (assoc parser :state :catches))
                (finally? form) (recur (assoc parser :state :finally))
                :else (recur (update-in parser* [:body] conj form)))
            :catches
              (cond
                (default? form)
                  (let [[_ _ name & dbody] form
                        default {:name name :expr (implicit-do dbody)}]
                    (recur (assoc parser* :default default :state :finally)))
                (catch? form)
                  (let [[_ type name & cbody] form
                        catch {:type type :name name
                               :expr (implicit-do cbody)}]
                    (recur (update-in parser* [:catches] conj catch)))
                (finally? form)
                  (recur (assoc parser :state :finally))
                :else
                  (throw (Exception. "Invalid try form")))
            :finally
              (let [[_ & fbody] form
                    finally (implicit-do fbody)]
                (recur (assoc parser* :finally finally :state :done)))
            :done
              (throw (Exception. "Unexpected form after finally"))))
        (-> parser
          (select-keys [:catches :default :finally])
          (assoc :head :try :form form :env env
                 :try (-> parser :body implicit-do)))))))

(defmethod parse-seq 'throw
  [[_ expr :as form] env]
  {:head :throw :form form :env env :expr expr})

(defmethod parse-seq 'def
  [[_ & body :as form] env]
  (let [[sym doc expr] (case (count body)
                         1 [(first body)]
                         2 [(first body) nil (second body)]
                         3 body)
        doc (or doc (-> sym meta :doc))
        sym (vary-meta sym assoc :doc doc)]
    (merge
      {:sym sym :form form :env env}
      (if (> (count body) 1)
        {:head :define :expr expr}
        {:head :declare}))))

(defmethod parse-seq 'new
  [[_ class & args :as form] env]
  {:head :new :form form :env env :class class :args (vec args)})

(defmethod parse-seq '.
  [[_ target & body :as form] env]
  (let [[member args] (if (and (= (count body) 1) (seq? (first body)))
                        [(ffirst body) (nfirst body)]
                        [(first body) (next body)])
        ;; syntax-quote may non-sensically qualify member symbols.
        member (symbol (name member))]
    {:head :interop :form form :env env
     :target target :member member :args (vec args)}))

(defmethod parse-seq 'set!
  [[_ location expr :as form] env]
  ;;TODO just :head :assign
  (if (symbol? location)
    {:head :assign-var :form form :env env
     :name location :expr expr}
    (let [[field object] location]
      ;;TODO: Validate location.
      {:head :assign-field :form form :env env
       :object object
       :field (symbol (apply str (next (str field))))
       :expr expr})))

(defmethod parse-seq 'loop*
  [[_ bindings & body :as form] env]
  (let [bindings (->> bindings
                      (partition 2)
                      (mapv (fn [[name init]]
                              {:name name
                               :init init})))]
    {:head :loop :form form :env env
     :bindings bindings :expr (implicit-do body)}))

(defmethod parse-seq 'recur
  [[_ & args :as form] env]
  {:head :recur :form form :env env :args (vec args)})

(defmethod parse-seq 'clojure.core/import*
  [[_ sym :as form] env]
  {:head :import :form form :env env :sym sym})

(defmethod parse-seq 'metaclj.impl.patch/case*
  [[_ expr cases default :as form] env]
  {:head :case :form form :env env
   :expr expr :cases cases :default default})

(defmethod parse-seq 'reify*
  [[_ interfaces & methods :as form] env]
  {:head :reify :form form :env env
   :interfaces interfaces :methods methods})

;;XXX This naive parsing assumes no deftype options & only internal usage.
(defmethod parse-seq 'deftype*
  [[_ tagname classname fields _ implements & methods :as form] env]
  {:head :deftype :form form :env env :tagname tagname :classname classname
   :fields fields :implements implements :methods methods})

(defmethod parse-seq 'clojure.core/unquote
  [[_ expr :as form] env]
  {:head :unquote :form form :env env :expr expr})

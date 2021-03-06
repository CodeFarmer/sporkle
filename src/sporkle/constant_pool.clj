(ns sporkle.constant-pool
  (:require [sporkle.core
             :refer [bytes-to-int bytes-to-long each-with-index]])
  (:require [clojure.string :as str]))


;; constant pool entries and their tag values

(def ^:const CONSTANT_Class               7)
(def ^:const CONSTANT_Fieldref	          9)
(def ^:const CONSTANT_Methodref	         10)
(def ^:const CONSTANT_InterfaceMethodref 11)
(def ^:const CONSTANT_String              8)

(def ^:const CONSTANT_Integer             3)
(def ^:const CONSTANT_Float               4)
(def ^:const CONSTANT_Long                5)
(def ^:const CONSTANT_Double              6)

(def ^:const CONSTANT_NameAndType        12)
(def ^:const CONSTANT_Utf8                1)

; will implement these last, can get a lot done without them
(def ^:const CONSTANT_MethodHandle       15)
(def ^:const CONSTANT_MethodType         16)
(def ^:const CONSTANT_InvokeDynamic      18)

(def WIDE_CONSTANTS #{CONSTANT_Double CONSTANT_Long})


;; constant pool methods, consider consolidating after you know what shape they are
;; pass the constant pool, as some things evaluate to indices into it which should be followed

(defn cp-nth
  "Retrieve the nth item from a constant pool, which is 1-indexed and in which certain constant types take up two entries"
  [cp n]
  (if (= 1 n)
    (first cp)
    (recur (rest cp)
           (if (WIDE_CONSTANTS (:tag (first cp)))
             (- n 2)
             (dec n)))))

(defmulti cp-entry-value #(:tag %2))

(defmethod cp-entry-value CONSTANT_Utf8 [constant-pool pool-entry]
  (:bytes pool-entry))

(defmethod cp-entry-value CONSTANT_Integer [constant-pool pool-entry]
  (:bytes pool-entry))

(defmethod cp-entry-value CONSTANT_Float [constant-pool pool-entry]
  (:bytes pool-entry))

(defmethod cp-entry-value CONSTANT_Long [constant-pool pool-entry]
  (bit-or (bytes-to-long (:low-bytes pool-entry))
          (bit-shift-left (bytes-to-long (:high-bytes pool-entry)) 32)))

(defmethod cp-entry-value CONSTANT_Double [constant-pool pool-entry]
  (Double/longBitsToDouble 
   (bit-or (bytes-to-long (:low-bytes pool-entry))
           (bit-shift-left (bytes-to-long (:high-bytes pool-entry)) 32))))

(defmethod cp-entry-value CONSTANT_NameAndType [constant-pool pool-entry]
  {:name (cp-entry-value constant-pool (cp-nth constant-pool (:name-index pool-entry)))
   :descriptor (cp-entry-value constant-pool (cp-nth constant-pool (:descriptor-index pool-entry)))})

(defmethod cp-entry-value CONSTANT_Class [constant-pool pool-entry]
  (cp-entry-value constant-pool (cp-nth constant-pool (:name-index pool-entry))))

   ;; TODO this whole (nth constant-pool blahblahblah) thing should be abstracted

(defn ref-value [constant-pool pool-entry]
  {:name-and-type (cp-entry-value constant-pool (cp-nth constant-pool (:name-and-type-index pool-entry)))
   :class (cp-entry-value constant-pool (cp-nth constant-pool (:class-index pool-entry)))})

;; It would be pretty great if we could have field and (particularly) method 
;; refs evaluate to symbols that were Java interop-like, so
;;
;; .foo
;; com.thing.Class/foo
;; .-foo
;;
;; But there would be information loss, as in the JVM these have types and can
;; be overloaded :(

(defmethod cp-entry-value CONSTANT_Methodref [constant-pool pool-entry]
  (ref-value constant-pool pool-entry))
   
(defmethod cp-entry-value CONSTANT_Fieldref [constant-pool pool-entry]
  (ref-value [constant-pool pool-entry]))

(defmethod cp-entry-value :default [java-class pool-entry]
  (throw (IllegalArgumentException. (str "Unable to interpret constant pool entry with tag " (format "0x%02X" (:tag pool-entry))))))


(defn constant-value
  ([constant-pool pool-index] 
     (cp-entry-value constant-pool (cp-nth constant-pool pool-index))))


(defn cp-find 
  "Find the cp-index into constant pool where a constant with particular contents can be found (remember, cp-indices start at one and have other potentially annoying behaviour)."
  [constant-pool value-map]
  (loop [indexed-cp (each-with-index constant-pool)]
    (if (empty? indexed-cp)
      nil
      (let [[c i] (first indexed-cp)]
        (if (= c value-map)
          (inc i)
          (recur (rest indexed-cp)))))))


(defn cp-find-utf8
  "Shortcut to cp-find for UTF-8 strings, which is a very common case"
  [constant-pool string]
  (cp-find constant-pool {:tag CONSTANT_Utf8 :bytes string}))


(defn cp-with-constant
  "Given a pool and a constant structure, return the constant pool containing the constant (adding it if necessary), and its index"
  [constant-pool constant-info]
  (if-let [index (cp-find constant-pool constant-info)]
    [constant-pool index]
    [(conj constant-pool constant-info) (+ 1 (count constant-pool))]))

(defn cp-with-utf8
  "Given a constant pool and a string, return a vector containing the constant pool with the Utf8 constant, and the cp index"
  [constant-pool string]
  (cp-with-constant constant-pool {:tag CONSTANT_Utf8 :bytes string}))


(defn cp-with-class
  "Given a constant pool and a string, return a vector containing the constant pool containing the string as a Utf8 constant and a Class constant whose name-index aligns with it, and the cp index of the class constant"
  [constant-pool string]
  (let [[new-cp idx] (cp-with-utf8 constant-pool string)]
    (cp-with-constant new-cp {:tag CONSTANT_Class :name-index idx})))


(defn cp-with-name-and-type [constant-pool name descriptor]

  (let [[new-cp name-index]       (cp-with-utf8 constant-pool name)
        [new-cp descriptor-index] (cp-with-utf8 new-cp descriptor)]

    (cp-with-constant new-cp {:tag CONSTANT_NameAndType :name-index name-index :descriptor-index descriptor-index})))


(defn cp-with-method [constant-pool class-name method-name descriptor]

  (let [[new-cp class-index]   (cp-with-class constant-pool class-name)
        [new-cp name-and-type-index] (cp-with-name-and-type new-cp method-name descriptor)]

    (cp-with-constant new-cp {:tag CONSTANT_Methodref :class-index class-index :name-and-type-index name-and-type-index})))


(ns sporkle.classfile  
  (:require [sporkle.core
             :refer [byte-from-unsigned byte-stream-seq bytes-to-int bytes-to-long four-byte-count int-to-byte-pair read-stream-maplets two-byte-index unpack-struct write-bytes MAGIC_BYTES MAJOR_VERSION MINOR_VERSION]])
  (:require [sporkle.bytecode
             :refer [syms-to-opcodes]])
  (:require [sporkle.constant-pool
             :refer [constant-value cp-find-utf8 cp-nth cp-with-utf8 CONSTANT_Class CONSTANT_Double CONSTANT_Integer CONSTANT_Fieldref CONSTANT_Float CONSTANT_InterfaceMethodref CONSTANT_InvokeDynamic CONSTANT_Long CONSTANT_MethodHandle CONSTANT_Methodref CONSTANT_MethodType CONSTANT_NameAndType CONSTANT_String CONSTANT_Utf8 WIDE_CONSTANTS]])
  (:require [clojure.java.io :as io]))

;; ClassFile {
;; 	u4 magic;
;; 	u2 minor_version;
;; 	u2 major_version;
;; 	u2 constant_pool_count;
;; 	cp_info constant_pool[constant_pool_count-1];
;; 	u2 access_flags;
;; 	u2 this_class;
;; 	u2 super_class;
;; 	u2 interfaces_count;
;; 	u2 interfaces[interfaces_count];
;; 	u2 fields_count;
;; 	field_info fields[fields_count];
;; 	u2 methods_count;
;; 	method_info methods[methods_count];
;; 	u2 attributes_count;
;; 	attribute_info attributes[attributes_count];
;; }

;; access flags

(def ^:const ACC_PUBLIC	      0x0001) ;; Class, method: may be accessed from outside its package.
(def ^:const ACC_PRIVATE      0x0002) ;; Class, method: accessible only within the defining class.
(def ^:const ACC_PROTECTED    0x0004) ;; Method: may be accessed within subclasses. method
(def ^:const ACC_STATIC       0x0008) ;; Method

(def ^:const ACC_FINAL	      0x0010) ;; Class: no subclasses allowed. Method: may not be overridden

; beware shared flag value
(def ^:const ACC_SUPER	      0x0020) ;; Class: treat superclass methods specially when invoked by the invokespecial instruction.
(def ^:const ACC_SYNCHRONIZED 0x0020) ;; Method: invocation is wrapped in a monitor lock.

(def ^:const ACC_NATIVE       0x0100) ;; Method: native method.
(def ^:const ACC_INTERFACE    0x0200) ;; Class: is an interface, not a class.
(def ^:const ACC_ABSTRACT     0x0400) ;; Class: may not be instantiated. Method: no implementation is provided
(def ^:const ACC_STRICT       0x0800) ;; Method: floating-point mode is FP-strict


;; this is necessary for two reasons
;; 1) The indices are 1-based!
;; 2) Some constant pool entries count for two spaces!

;; conforms to the expectations of read-stream-maplets
;; FIXME document this
(defmulti read-constant-pool-entry first)

(defmethod read-constant-pool-entry CONSTANT_Utf8 [bytes]
  (let [[tag & rest] bytes]
    (let [length (bytes-to-long (take 2 rest))]
      [{:tag tag :bytes (String. (byte-array (map byte-from-unsigned (take length (drop 2 rest)))))}
       (drop (+ length 2) rest)])))

(defmethod read-constant-pool-entry CONSTANT_Integer [bytes]
  (unpack-struct [[:tag 1] [:bytes 4 bytes-to-long]] bytes))

; NOTE these next three are the same at the moment but that will change
(defmethod read-constant-pool-entry CONSTANT_Methodref [bytes]
  (unpack-struct [[:tag 1] [:class-index 2] [:name-and-type-index 2]] bytes))
(defmethod read-constant-pool-entry CONSTANT_Fieldref [bytes]
  (unpack-struct [[:tag 1] [:class-index 2] [:name-and-type-index 2]] bytes))
(defmethod read-constant-pool-entry CONSTANT_InterfaceMethodref [bytes]
  (unpack-struct [[:tag 1] [:class-index 2] [:name-and-type-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_Class [bytes]
  (unpack-struct [[:tag 1] [:name-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_NameAndType [bytes]
  (unpack-struct [[:tag 1] [:name-index 2] [:descriptor-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_String [bytes]
  (unpack-struct [[:tag 1] [:string-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_Float [bytes]
  (unpack-struct [[:tag 1] [:bytes 4 #(Float/intBitsToFloat (bytes-to-int %))]] bytes))

(defmethod read-constant-pool-entry CONSTANT_Long [bytes]
  (unpack-struct [[:tag 1] [:high-bytes 4] [:low-bytes 4]] bytes))
(defmethod read-constant-pool-entry CONSTANT_Double [bytes]
  (unpack-struct [[:tag 1] [:high-bytes 4] [:low-bytes 4]] bytes))

(defmethod read-constant-pool-entry CONSTANT_MethodHandle [bytes]
  (unpack-struct [[:tag 1] [:reference-kind 1] [:reference-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_MethodType [bytes]
  (unpack-struct [[:tag 1] [:descriptor-index 2]] bytes))

(defmethod read-constant-pool-entry CONSTANT_InvokeDynamic [bytes]
  (unpack-struct [[:tag 1] [:bootstrap-method-attr-index 2] [:name-and-type-index 2]] bytes))

(defn hex [b]
  (format "0x%02X" b))

(defmethod read-constant-pool-entry :default [bytes]
  (let [tag (first bytes)]
    (if (nil? tag)
      (throw (IllegalArgumentException. (str "Unable to read in constant pool entry with nil tag")))
      (throw (IllegalArgumentException. (str "Unable to read in constant pool entry with tag " (hex (first bytes)) " (next few " (vec (map hex (take 4 (rest bytes)))) ")"))))))


(defn read-struct-list-maplet

  ([key readfn bytes]

     (let [count (bytes-to-long (take 2 bytes)) remainder (drop 2 bytes)]
       (read-struct-list-maplet [] count key readfn remainder)))

  ([acc count key readfn bytes]
     (if (zero? count) [{key acc} bytes]
         (let [[descriptor remainder] (readfn bytes)]
           (recur (conj acc descriptor) (dec count) key readfn remainder)))))

;; read-attribute ;; ;; read-attribute ;; ;; read-attribute ;; ;; read-attribute ;; ;; read-attribute ;;

(declare read-attribute)

;; attributes are structured according to their names

(defn -attribute-info-kind [constant-pool index-bytes _]
  (if (nil? constant-pool)
    :default ;; useful for debugging or testing
    (constant-value constant-pool (bytes-to-long index-bytes))))

(defmulti unpack-attribute-info -attribute-info-kind)

(defn read-code-maplet [bytes]
  (let [count (bytes-to-long (take 4 bytes))
        info (take count (drop 4 bytes))]
    [{:code info} (drop (+ 4 count) bytes)]))

(defn read-exception-table-maplet [bytes]
  (read-struct-list-maplet :exception-table #(unpack-struct [[:start-pc 2] [:end-pc 2] [:handler-pc 2] [:catch-type 2]] %) bytes))

;; this one comes up a few times
(defn read-attributes-maplet [constant-pool bytes]
  (read-struct-list-maplet :attributes (partial read-attribute constant-pool) bytes))

(defmethod unpack-attribute-info "Code" [constant-pool name-index info-bytes]
  (first
   (read-stream-maplets
    [#(unpack-struct [[:max-stack 2] [:max-locals 2]] %)
     read-code-maplet
     read-exception-table-maplet
     (partial read-attributes-maplet constant-pool)]
    info-bytes)))

(defmethod unpack-attribute-info :default [constant-pool name-index info-bytes]
  {:info info-bytes})

(defn read-attribute [constant-pool bytes]

  (let [name-index (take 2 bytes)
        count (bytes-to-long (take 4 (drop 2 bytes)))
        remainder (drop 6 bytes)]

    [(into {:attribute-name-index (bytes-to-long name-index)}
           (unpack-attribute-info constant-pool name-index (take count remainder)))
     (drop count remainder)]))

;; ;;


(defn read-cp-entry-list-maplet
  "This should be read-struct-list-maplet, except that for long and double constants you need to bump the index twice. No, me either."
  ;; "In retrospect, making 8-byte constants take two constant pool entries was a poor choice." :P
  [acc count key readfn bytes]
  (if (zero? count) [{key acc} bytes]
      (let [[descriptor remainder] (readfn bytes)]
        (recur (conj acc descriptor)
               (if (WIDE_CONSTANTS (:tag descriptor))
                 (- count 2)
                 (dec count))
               key
               readfn
               remainder))))

;; the constant pool, annoyingly, has a different way of expressing its length than every other struct list
(defn read-constant-pool-maplet [bytes]
  (let [count (dec (bytes-to-long (take 2 bytes))) remainder (drop 2 bytes)]
    (read-cp-entry-list-maplet [] count :constant-pool read-constant-pool-entry remainder)))


;; constant-pool is needed for read-attributes
(defn read-field-or-method-info [constant-pool bytes]
  (read-stream-maplets
   [#(unpack-struct [[:access-flags 2 bytes-to-long] [:name-index 2 bytes-to-long] [:descriptor-index 2 bytes-to-long]] %)
    (partial read-attributes-maplet constant-pool)]
   bytes))


;; an interface index is just a byte pair
(defn read-byte-pair [bytes]
  [(take 2 bytes) (drop 2 bytes)])


;; for quick-hack debugging
(defn -print-some-stream-bytes [bytes]
  (println (map #(format "0x%02x" %) (take 256 bytes)))
  [{} bytes])


;; the overall stream-to-class function
(defn read-java-class [bytes]
  ;; need to read the constant pool first, mostly for the benefit of attribute unpacking logic
  (let [[partial-class remainder]
        (read-stream-maplets
         [#(unpack-struct [[:magic 4] [:minor-version 2 bytes-to-long] [:major-version 2 bytes-to-long]] %)
          read-constant-pool-maplet]
         bytes)
        constant-pool (:constant-pool partial-class)]

    (into partial-class
          (first
           (read-stream-maplets
            ;; FIXME DRY this up
            [#(unpack-struct [[:magic 4] [:minor-version 2 bytes-to-long] [:major-version 2 bytes-to-long]] %)
             read-constant-pool-maplet
             #(unpack-struct [[:access-flags 2 bytes-to-long] [:this-class 2 bytes-to-long] [:super-class 2 bytes-to-long]] %)
             #(read-struct-list-maplet :interfaces read-byte-pair %)
             #(read-struct-list-maplet :fields     (partial read-field-or-method-info constant-pool) %)
             #(read-struct-list-maplet :methods    (partial read-field-or-method-info constant-pool) %)
             (partial read-attributes-maplet constant-pool)]
            bytes)))))

(defn read-java-class-file
  "Convenience method; read a java-class map from a named file"
  [filename]
  (with-open [stream (io/input-stream filename)]
    (read-java-class (doall (byte-stream-seq stream)))))

;; for something with a name-index, get its name
(defn get-name
  "For anything that has a name-index in its struct, return the string represented in the class by that name-index. If no name-index, return nil.

NOTE not called 'name' like the others of its ilk in order not to clash"
  [constant-pool thing]
  (if (nil? (:name-index thing))
    nil
    (constant-value constant-pool (:name-index thing))))

;; FIXME everything below needs a test
;; FIXME everything below needs a test
;; FIXME everything below needs a test
;; FIXME everything below needs a test



(defn descriptor
  "Return the descriptor string for a method (or anything else with a descriptor-index"
  [constant-pool meth]
  (constant-value constant-pool (:descriptor-index meth)))

;; consider making this internal
(defn indexed-name
  "Given a constant pool and a two-byte index, find the constant pointed to by the index (for example a Class or a NameAndType), and resolve its name-index attribute to a string"
  [constant-pool index]
  (get-name constant-pool (cp-nth constant-pool index)))


(defn class-name [java-class]
  (let [this-class (:this-class java-class)]
    (if (nil? this-class)
      nil
      (indexed-name (:constant-pool java-class) this-class))))

;; FIXME REFACTOR this is the same as class-name
(defn super-class-name [java-class]
  (let [super-class (:super-class java-class)]
    (if (nil? super-class)
      nil
      (indexed-name (:constant-pool java-class) super-class))))


;; only necessary because attributes don't have a name-index
(defn attribute-name
  "see get-name"
  [constant-pool attribute]
  (if (nil? (:attribute-name-index attribute))
    nil
    (constant-value constant-pool (:attribute-name-index attribute))))


(defn interface-names
  "Return an array of strings that are the qualified classnames of a class' implemented interfaces"
  [java-class]
  (map (partial indexed-name java-class) (:interfaces java-class)))


(defn attribute-named
  "Retrieve the :attributes member of thing whose attribute-name-index corresponds to the location of 'name' in the constant pool"
  [constant-pool thing name]
  (when-let [idx (cp-find-utf8 constant-pool name)]
    (loop [attribs (seq (:attributes thing))]
      (if (empty? attribs)
        nil
        (let [attrib (first attribs)]
          (if (= idx (:attribute-name-index attrib))
            attrib
            (recur (rest attribs))))))))



;; writing classfiles

;; has test
(defn write-class-header [stream]
  (write-bytes stream MAGIC_BYTES)
  (write-bytes stream MINOR_VERSION)
  (write-bytes stream MAJOR_VERSION))

(defmulti constant-pool-entry-bytes :tag)

(defmethod constant-pool-entry-bytes CONSTANT_Utf8 [cp-entry]
  ;; not convinced this is a great idea
  (flatten [(:tag cp-entry) (two-byte-index (count (:bytes cp-entry))) (seq (.getBytes (:bytes cp-entry)))]))

(defmethod constant-pool-entry-bytes CONSTANT_Integer [cp-entry]
  ;; take 4 ensures you don't over/underrun in case of mangled fields, is this actually useful?
  (cons (:tag cp-entry) (take 4 (:bytes cp-entry))))
(defmethod constant-pool-entry-bytes CONSTANT_Float [cp-entry]
  (cons (:tag cp-entry) (take 4 (:bytes cp-entry))))

(defn ref-bytes [cp-entry]
  ;; these are all already starting to look very repetitive, and they also duplicate information
  ;; in read-constant-pool-entry - FIXME
  (flatten [(:tag cp-entry) (two-byte-index (:class-index cp-entry)) (two-byte-index (:name-and-type-index cp-entry))]))

(defmethod constant-pool-entry-bytes CONSTANT_Methodref [cp-entry]
  (ref-bytes cp-entry))
(defmethod constant-pool-entry-bytes CONSTANT_Fieldref [cp-entry]
  (ref-bytes cp-entry))
(defmethod constant-pool-entry-bytes CONSTANT_InterfaceMethodref [cp-entry]
  (ref-bytes cp-entry))

(defmethod constant-pool-entry-bytes CONSTANT_Class [cp-entry]
  (cons (:tag cp-entry) (two-byte-index (:name-index cp-entry))))

(defmethod constant-pool-entry-bytes CONSTANT_NameAndType [cp-entry]
  (flatten [(:tag cp-entry) (two-byte-index (:name-index cp-entry)) (two-byte-index (:descriptor-index cp-entry))]))

;; FIXME pretty sure flatten is the wrong idiom here
(defmethod constant-pool-entry-bytes CONSTANT_String [cp-entry]
  (cons (:tag cp-entry) (two-byte-index (:string-index cp-entry))))

(defmethod constant-pool-entry-bytes CONSTANT_Long [cp-entry]
  (flatten [(:tag cp-entry) (:high-bytes cp-entry) (:low-bytes cp-entry)]))
(defmethod constant-pool-entry-bytes CONSTANT_Double [cp-entry]
  (flatten [(:tag cp-entry) (:high-bytes cp-entry) (:low-bytes cp-entry)]))


(defn opcodes-to-code-bytes

  "Given a constant pool and pseudo-bytecode, return the constant pool plus a stream of bytes, potentially by updating the pool with constants generated from literals in the code seq"

  ;; FIXME laxy seq please
  
  ([cp pseudocode]

     (opcodes-to-code-bytes cp pseudocode []))

  ([cp pseudocode acc]

     (if (empty? pseudocode)
       [cp acc]

       (let [[op-symbol & remainder] pseudocode
             op (get syms-to-opcodes op-symbol)]

         (if (nil? op)

           (throw (IllegalArgumentException. (str "Unable to find operation for code item '" op-symbol "'; next few " (take 4 remainder))))

           (let [[_ op-byte _ _ bc-arg-fn] op]

             (if (nil? bc-arg-fn)

                                        ;;; FIXME all this (flatten...) shit is awful no args
              
               (opcodes-to-code-bytes cp remainder (flatten [acc op-byte]))

                ;; some args
                
               (let [[cp arg-bytes remainder] (bc-arg-fn cp remainder)]

                 (opcodes-to-code-bytes cp remainder (flatten [acc op-byte arg-bytes]))))

             ))))))


(defn cp-with-code-attribute

  "Return a suitably updated constant pool, plus a Code Attribute (see JVM spec section 4.7.3) corresponding to the opcodes (translated into a byte array). Presently returns no exception table or further attributes."

  [cp max-stack max-locals pseudocode]

  (let [[cp name-index] (cp-with-utf8 cp "Code")
        [cp code-bytes] (opcodes-to-code-bytes cp pseudocode)
        code-count (count code-bytes)]

    [cp
     {:attribute-name-index   name-index
      ;; FIXME this assumes no exception table etc
      :attribute-length       (four-byte-count (+ code-count 12))
      :max-stack              (int-to-byte-pair max-stack)
      :max-locals             (int-to-byte-pair max-locals)
      :code-length            (four-byte-count code-count)
      :code                   code-bytes
      :exception-table-length [0x00 0x00]
      :exception-table        []
      :attributes-count       [0x00 0x00]
      :attributes             []}]))
  
(defmethod constant-pool-entry-bytes :default [cp-entry]
  (throw (IllegalArgumentException. (str "Unable to make flat bytes for pool entry with tag " (format "0x%02X" (:tag cp-entry))))))

; unit test?
(defn write-constant-pool-entry [stream entry]
  (write-bytes stream (constant-pool-entry-bytes entry)))

;; pool-writey stuff all needs tests
(defn write-constant-pool [stream pool]
  ;; the necessity for (inc pool) is deeply weird but.. RTFS I guess.
  (write-bytes stream (inc (count pool)))
  (doseq [p pool]
    (write-constant-pool-entry stream p)))


;; this is going to be annoying, since attrs will require the class for
;; dispatch!
(defn write-thing-list [stream write-fn thing-list]
  (write-bytes stream (count thing-list))
  (doseq [t thing-list]
    (write-fn stream t)))


(defn write-interface [stream field])

(defn write-method [stream method])

;; attribute-length ;; ;; attribute-length ;; ;; attribute-length ;; 

(defmulti attribute-length attribute-name)

;; write-attribute ;; ;; write-attribute ;; ;; write-attribute ;;

(defmulti write-attribute #(attribute-name %2 %3))

(defmethod write-attribute :default [stream constant-pool attribute]
  (let [info (:info attribute)]
    (if (nil? info)
      (throw (IllegalArgumentException. "Fell through to default write-attribute implementation, but attribute has no info")))
    (write-bytes stream (:attribute-name-index attribute))
    (write-bytes stream (four-byte-count (count (:info attribute))))
    (write-bytes stream (:info attribute))))


(defn write-attributes
  "Write the attributes of anything (class, method, field, etc) with reference to the constant pool of java-class for decoding name-indices. if no thing is supplied, write the attributes of the java-class itself."
  ([stream java-class]
     (write-attributes stream (:constant-pool java-class) java-class))
  ([stream constant-pool thing]
     (let [attribs (:attributes thing)]
       (write-bytes stream (count attribs))
       (doseq [a attribs]
         (write-attribute stream constant-pool a)))))

(defmethod write-attribute "Code" [stream constant-pool attribute]
  (write-bytes stream (:attribute-name-index   attribute))
  (write-bytes stream (:attribute-length       attribute))
  (write-bytes stream (:max-stack              attribute))
  (write-bytes stream (:max-locals             attribute))
  (write-bytes stream (:code-length            attribute))
  (write-bytes stream (:code                   attribute))
  (write-bytes stream (:exception-table-length attribute))
  ;; FIXME write exception table, when that exists
  (write-attributes stream constant-pool attribute))


;; this is ugly, just being able to make it partial is not a good
;; enough reason to make it inconsistent
(defn write-field [constant-pool stream field]
  (write-bytes stream (:access-flags field))
  (write-bytes stream (:name-index field))
  (write-bytes stream (:descriptor-index field))
  (write-attributes stream constant-pool field))

(defn write-java-class [stream java-class]

  (let [cp (:constant-pool java-class)]
    
    (write-class-header   stream)
    (write-constant-pool  stream cp)
    (write-bytes          stream (:access-flags  java-class))
    (write-bytes          stream (:this-class    java-class))
    (write-bytes          stream (:super-class   java-class))
    
                                        ; interfaces are just two-byte indices anyway
    (write-thing-list stream write-bytes (:interfaces java-class))
    
    (write-thing-list stream (partial write-field cp)     (:fields     java-class))
    ;; is write-field also write-method?
    (write-thing-list stream (partial write-field cp)     (:methods     java-class))
    (write-attributes stream java-class)
    stream))

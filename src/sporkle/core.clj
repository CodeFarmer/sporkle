(ns sporkle.core)


(defn byte-stream-seq
  "Returns a lazy sequence of bytes from a java.io.InputStream"

  [^java.io.InputStream stream]
  (let [b (.read stream)]
    (if (= b -1)
      ()
      (cons b (lazy-seq (byte-stream-seq stream))))))


(defn bytes-to-unsigned-integral-type
  
  "Given a big-endian stream of bytes, convert those to a long of the correct value"
  
  ([bytes]
     (if (empty? bytes)
       nil
       (bytes-to-unsigned-integral-type 0 bytes)))

  ([acc bytes]
     (if (empty? bytes)
       acc
       (recur (+ (first bytes) (bit-shift-left acc 8)) (rest bytes)))))

(defn bytes-to-integral-type

  "Given a big-endian stream of bytes, convert those to a long of the correct signed value"
  
  ([bytes]
     (if (empty? bytes)
       nil
       (if (zero? (bit-and 0x80 (first bytes)))
         (bytes-to-unsigned-integral-type 0 bytes)
         (bytes-to-integral-type 0 (cons (- (first bytes) 0x80) (rest bytes))))))

  ([acc bytes]
     (if (empty? bytes)
       (- acc)
       (recur (+ (first bytes) (bit-shift-left acc 8)) (rest bytes)))))


(defn unpack-struct
  "Given a list of [:key integer & flags] and a seq, return a vec containing 1) a map whose keys are all the keys from the pairs, plus integers (or longs or bigs) made of from the requisite numbers of bytes from aseq each (in the order they appear in avec), and 2) the remainder of the seq.

Partial applications conform to the expectations of read-stream-maplets.

As yet, the only flag that is defined is :unsigned, which does what you would expect"
  
  ([avec aseq]
     (unpack-struct {} avec aseq))

  ([amap avec aseq]
     
     (let [[field-key field-size & flags] (first avec)
           field-data (take field-size aseq)
           convert-fn (if (some #(= :unsigned %) flags) bytes-to-unsigned-integral-type bytes-to-integral-type)]
       
       (cond
        (nil? field-key) [amap aseq] ;; this is the return value
        (< field-size (count field-data)) (throw (IndexOutOfBoundsException. (str "Ran out of stream unpacking struct field " field-key ", needed " field-size ", got " field-data)))
        :else (recur (assoc amap field-key (convert-fn field-data)) (rest avec) (drop field-size aseq))))))


;; Dammit the threading macro is *so* close to what I need
(defn read-stream-maplets
  "Given a (seq of (functions that take a seq and return a map plus the unread portion of the seq)) and an input seq, return the combination of all the maps created by running the functions in series, each on the remaining seq left after its predecessor, starting with the input seq.

Returns a pair [map, remainder], so it can nest within itself"

  ([funcs bytes]
     (read-stream-maplets {} funcs bytes))

  ([acc funcs bytes]
     (let [f (first funcs)]
       (if (nil? f) [acc bytes]
           (let [[maplet remainder] (f bytes)]
             (recur (merge acc maplet) (rest funcs) remainder))))))

(ns sporkle.test.classfile
  (:use [sporkle.core])
  (:use [sporkle.classfile])
  (:use [clojure.test])
  (:require [clojure.java.io :as io]))

;; (deftest read-constant-pool-entry)

(deftest test-read-constant-pool-entry

  (testing "Reading from a known UTF8 constant from a classfile, with some trailing bytes"

    (let [[entry rest] (read-constant-pool-entry
                        [0x01 0x00 0x0C 0x4E  0x6F 0x74 0x68 0x69
                         0x6E 0x67 0x2E 0x6A  0x61 0x76 0x61 0x0C
                         ;; 0x61 is actually the last byte ^ of the entry
                         00 04])]
                          
      (is (= [0x0C 0x00 0x04] rest)
          "should correctly return the remaining bytes")
      (is (= CONSTANT_Utf8 (:tag entry))
          "should correctly set the tag")
      (is (= 12 (:length entry))
          "should read the two-byte length correctly")
      (is (= [0x4E 0x6F 0x74 0x68 0x69 0x6E 0x67 0x2E 0x6A 0x61 0x76 0x61] (:bytes entry))
          "should read the correct bytes")
      (is (= "Nothing.java" (apply str (map char (:bytes entry))))
          "should give us back bytes that can be turned into a Java String")))
  
  (testing "Reading an integer constant, with a trailing byte"

    (let [[entry rest] (read-constant-pool-entry [0x03 0x00 0x10 0x01 0x01 0xFF])]
      
      (is (= [0xFF] rest)                          "should correctly return the remaining bytes")
      (is (= [0x00 0x10 0x01 0x01] (:bytes entry)) "should read only the two integer bytes")))

  (testing "reading a method ref entry, with a trailing byte"

    (let [[entry rest] (read-constant-pool-entry [0x0A 0x00 0x03 0x00 0x0A])]
    (is (= CONSTANT_Methodref (:tag entry)) "should record the correct tag")
    (is (= [0x00 0x03] (:class-index entry)) "should record the class index bytes"))

  (testing "reading a field ref entry, with a trailing byte"

    (let [[entry rest] (read-constant-pool-entry [0x09 0x00 0x03 0x00 0x0A])]
      (is (= CONSTANT_Fieldref (:tag entry)) "should record the correct tag")
      (is (= [0x00 0x03] (:class-index entry)) "should record the class index bytes")
      (is (= [0x00 0x0A] (:name-and-type-index entry "should record the name and type index bytes")))))

  (testing "reading an interface method ref entry, with a trailing byte"

    (let [[entry rest] (read-constant-pool-entry [0x0B 0x00 0x03 0x00 0x0A])]
      (is (= CONSTANT_InterfaceMethodref (:tag entry)) "should record the correct tag")
      (is (= [0x00 0x03] (:class-index entry)) "should record the class index bytes")
      (is (= [0x00 0x0A] (:name-and-type-index entry "should record the name and type index bytes"))))))


  (testing "Reading from a constant with an unknown tag value"
    
    (is (thrown? IllegalArgumentException (read-constant-pool-entry [0x0D 0xFF 0xFF])))))


(deftest test-read-constant-pool
  (testing "with the constant pool bytes from a small class"
    (let [[constant-pool remainder] (read-constant-pool (drop 8 (byte-stream-seq (io/input-stream "test/fixtures/Nothing.class"))))]
      (is (= 12 (count constant-pool)) "should read the right number of constants")
      (is (every? #(contains? % :tag) constant-pool) "should return a seq of objects with tag fields")
      ;; IMPLEMENT ME
      )))

(comment (deftest test-read-java-class
    (testing "reading a simple class"
      (let [java-class (read-java-class (byte-stream-seq (io/input-stream "test/fixtures/Nothing.class")))]
        (is (= [0xCA 0xFE 0xBA 0xBE] (:magic java-class)) "gotta get the magic number right")
        (is (= [0x00 0x00] (:minor-version java-class))   "minor version number of the class file")
        (is (= [0x00 0x32] (:major-version java-class))   "major version number of the class file")
        (is (= 13 (count (:constant-pool java-class))     "should read the correct number of constant pool entries"))
        ;; ... and the rest, but the constants are necessary first
        ))))
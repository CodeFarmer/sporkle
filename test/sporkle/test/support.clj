(ns sporkle.test.support
  (:require [clojure.test :refer [deftest is testing]])
  (:require [clojure.java.io :as io])
  (:require [sporkle.core :refer [read-stream-to-byte-array]])
  (:import  [sporkle.test ByteLoader]))


(deftest test-class-loader
  (testing "That our wrapped classloader interface can read known-correct (ie., compiled by javac) byte arrays"
    (with-open [s (io/input-stream "test/fixtures/Nothing.class")]
      (let [bl (ByteLoader.)
            clazz (.loadBytes bl (read-stream-to-byte-array s))]

        (is (class? clazz) "should read a useable class object back")
        (is (= "Nothing" (.getName clazz)) "should be able to read the correct class name")))))


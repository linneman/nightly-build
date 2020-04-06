;; Crypto Functions
;;
;; The use and distribution terms for this software are covered by
;; the GNU General Public License
;;
;; (C) 2019, Otto Linnemann
;;;
;;; taken from, many thanks to:
;;; https://github.com/mmcgrana/ring/blob/master/ring-core/src/ring/middleware/session/cookie.clj
;;; password hashing function (hash-password) from Joerg Ramb


(ns server.crypto
  "Encrypted cookie session storage."
  (:use ring.middleware.session.store)
  (:require [ring.util.codec :as codec])
  (:import java.security.SecureRandom
           java.security.MessageDigest
           (javax.crypto Cipher Mac)
           (javax.crypto.spec SecretKeySpec IvParameterSpec)))

(def ^{:private true
       :doc "Algorithm to seed random numbers."}
  seed-algorithm
  "SHA1PRNG")

(def ^{:private true
       :doc "Algorithm to generate a HMAC."}
  hmac-algorithm
  "HmacSHA256")

(def ^{:private true
       :doc "Type of encryption to use."}
  crypt-type
  "AES")

(def ^{:private true
       :doc "Full algorithm to encrypt data with."}
  crypt-algorithm
  "AES/CBC/PKCS5Padding")

(defn- secure-random-bytes
  "Returns a random byte array of the specified size."
  [size]
  (let [seed (byte-array size)]
    (.nextBytes (SecureRandom/getInstance seed-algorithm) seed)
    seed))

(defn- hmac
  "Generates a Base64 HMAC with the supplied key on a string of data."
  [key data]
  (let [mac (Mac/getInstance hmac-algorithm)]
    (.init mac (SecretKeySpec. key hmac-algorithm))
    (codec/base64-encode (.doFinal mac data))))

(defn- encrypt
  "Encrypt a string with a key."
  [key data]
  (let [cipher     (Cipher/getInstance crypt-algorithm)
        secret-key (SecretKeySpec. key crypt-type)
        iv         (secure-random-bytes (.getBlockSize cipher))]
    (.init cipher Cipher/ENCRYPT_MODE secret-key (IvParameterSpec. iv))
    (->> (.doFinal cipher data)
      (concat iv)
      (byte-array))))

(defn- decrypt
  "Decrypt an array of bytes with a key."
  [key data]
  (let [cipher     (Cipher/getInstance crypt-algorithm)
        secret-key (SecretKeySpec. key crypt-type)
        [iv data]  (split-at (.getBlockSize cipher) data)
        iv-spec    (IvParameterSpec. (byte-array iv))]
    (.init cipher Cipher/DECRYPT_MODE secret-key iv-spec)
    (String. (.doFinal cipher (byte-array data)))))


(defn get-secret-key
  "Get a valid secret key from a map of options, or create a random one from
  scratch."
  [options]
  (if-let [secret-key (:key options)]
    (if (string? secret-key)
      (.getBytes ^String secret-key)
      secret-key)
    (secure-random-bytes 16)))


(defn hash-password [password salt]
  "returns strong password hash according to recommendation
   from Joerg Ramb. Many thanks!"
  (assert (> (count salt) 10))          ;would like to have >64 bit of salt
  (assert (> (count password) 4))       ;come on, how low can we go?
  (let [md (java.security.MessageDigest/getInstance "SHA-512")
        encoder (sun.misc.BASE64Encoder.)]
    (.update md (.getBytes salt "UTF-8")) ;assume text salt
    (.encode encoder
             (loop [mangle (.getBytes password "UTF-8")
                    passes 10]         ; paranoid, but are we paranoid enough?
               (if (= 0 passes)
                 mangle
                 (recur (.digest md mangle) (dec passes)))))))


(defn get-encrypt-pass-and-salt
  "returns a map with the encrypted password and
   a salt string used for encryption"
  [password]
  (let [salt (codec/base64-encode (get-secret-key {}))
        enc-password (hash-password password salt)]
    {:password (String. enc-password) :salt salt}))


(comment
  usage illustration

  (def enc (get-encrypt-pass-and-salt "secret"))
)


(defn base64-sha1
  "computes base64 string of sha1 hash value of given string"
  [str]
  (let [sha1 (MessageDigest/getInstance "SHA1")
        digest (. sha1 (digest (.getBytes str)))]
    (codec/base64-encode digest)))

(ns ^:no-doc onyx.messaging.protocol-netty
    (:require [taoensso.timbre :as timbre]
              [onyx.types :refer [->Leaf]])
    (:import [java.util UUID]
             [io.netty.buffer ByteBuf Unpooled UnpooledByteBufAllocator
              PooledByteBufAllocator ByteBufAllocator]))

;;;;;;
;; helpers
(defn byte-buffer [size]
  (.heapBuffer ^ByteBufAllocator PooledByteBufAllocator/DEFAULT size))

(defn write-uuid [^ByteBuf buf ^UUID uuid]
  (.writeLong buf (.getMostSignificantBits uuid))
  (.writeLong buf (.getLeastSignificantBits uuid)))

(defn take-uuid [^ByteBuf buf]
  (let [msb (.readLong buf)
        lsb (.readLong buf)] 
    (java.util.UUID. msb lsb)))

;;;;;;
;; Constants
(def ^:const messages-type-id ^byte (byte 0))
(def ^:const ack-type-id ^byte (byte 1))
(def ^:const completion-type-id ^byte (byte 2))
(def ^:const retry-type-id ^byte (byte 3))

(def ^:const type-header-length (int 1))

; id uuid 
(def ^:const completion-msg-length (int 16))

; id uuid 
(def ^:const retry-msg-length (int 16))

(def ^:const acks-base-length (int 4))
; id uuid, completion-id uuid, ack-val long
(def ^:const ack-base-length (int 40))
(def ^:const acks-header-length (int (+ acks-base-length type-header-length)))

; message length without nippy segments
; id (uuid), acker-id (uuid), completion-id (uuid), ack-val (long)
(def ^:const message-base-length (int 56))

; messages with 0 messages in it
(def ^:const messages-base-length (int 4))

(def ^:const completion-payload-length (int (+ completion-msg-length type-header-length)))

(def ^:const retry-payload-length (int (+ retry-msg-length type-header-length)))


(defn build-completion-msg-buf [id] 
  (let [buf ^ByteBuf (byte-buffer completion-payload-length)] 
    (.writeByte buf completion-type-id)
    (write-uuid buf id)
    buf))

(defn build-retry-msg-buf [id] 
  (let [buf ^ByteBuf (byte-buffer retry-payload-length)] 
    (.writeByte buf retry-type-id)
    (write-uuid buf id)
    buf))

(defn read-completion-buf [^ByteBuf buf]
  {:type completion-type-id 
   :id (take-uuid buf)})

(defn read-retry-buf [^ByteBuf buf]
  {:type retry-type-id 
   :id (take-uuid buf)})

(defn build-acks-msg-buf [acks] 
  (let [cnt (count acks)
        ^ByteBuf buf (byte-buffer (+ acks-base-length (* cnt ack-base-length)))] 
    (.writeByte buf ack-type-id)
    (.writeInt buf (int cnt))
    (doseq [ack acks]
      (write-uuid buf (:id ack))
      (write-uuid buf (:completion-id ack))
      (.writeLong buf (:ack-val ack)))
    buf))

(defn read-acks-buf [^ByteBuf buf]
  (let [cnt (.readInt buf)] 
    {:type ack-type-id
     :acks (doall 
             (repeatedly cnt 
                         (fn [] 
                           (let [id (take-uuid buf)
                                 completion-id (take-uuid buf)
                                 ack-val (.readLong buf)]
                             {:id id 
                              :completion-id completion-id
                              :ack-val ack-val}))))}))

(defn write-message-msg [^ByteBuf buf {:keys [id acker-id completion-id ack-val message]}]
  (write-uuid buf id)
  (write-uuid buf acker-id)
  (write-uuid buf completion-id)
  (.writeLong buf ack-val))

(defn read-message-buf [decompress-f ^ByteBuf buf message]
  (let [id (take-uuid buf)
        acker-id (take-uuid buf)
        completion-id (take-uuid buf)
        ack-val (.readLong buf)]
    ;;; Maybe should have a new record type for the read messages
    (->Leaf message id acker-id completion-id ack-val nil nil nil nil)))

(defn build-messages-msg-buf [compress-f messages] 
  (let [message-bytes ^bytes (compress-f (map :message messages))
        message-count (int (count messages))
        buf-size (+ type-header-length
                    (+ 4 ; message count int
                       (alength message-bytes)
                       (* message-base-length message-count)))
        buf ^ByteBuf (byte-buffer buf-size)] 
    (.writeByte buf messages-type-id) ; message type header
    (.writeInt buf message-count) ; number of messages
    (.writeInt buf (alength message-bytes)) ; nippy compressed data size
    (.writeBytes buf message-bytes) ; nippy compressed messages
    (doseq [msg messages]
      (write-message-msg buf msg))
    buf))

(defn read-messages-buf [decompress-f ^ByteBuf buf]
  {:type messages-type-id 
   :messages (let [message-count (.readInt buf)
                   messages-payload-size (.readInt buf)
                   arr (byte-array messages-payload-size)
                   _ (.readBytes buf arr)
                   messages (decompress-f arr)]
               (doall (map (fn [msg] 
                             (read-message-buf decompress-f buf msg)) 
                           messages)))})

(defn read-buf [decompress-f ^ByteBuf buf]
  (let [msg-type ^byte (.readByte buf)] 
    (cond (= msg-type messages-type-id) 
          (read-messages-buf decompress-f buf)
          (= msg-type ack-type-id) 
          (read-acks-buf buf)
          (= msg-type completion-type-id) 
          (read-completion-buf buf)
          (= msg-type retry-type-id)
          (read-retry-buf buf)
          :else (throw (Exception. (str "Invalid message type: " msg-type))))))




(ns hirop-redis-store.core
  (:use hirop.core
        hirop.protocols)
  (:require [taoensso.carmine :as car]))

(defmacro wcar
  [& body]
  `(let [{pool# :pool spec# :spec} @~'conn-atom]
     (car/with-conn pool# spec# ~@body)))

(defprotocol IRedisStore
  (reset-conn [this pool spec]))

(deftype RedisStore [conn-atom prefix expiration]
  IContextStore
  (get-context [_ context-id]
    (wcar (car/get context-id)))
  
  (put-context [_ context]
    (let [context-id (str prefix (uuid))]
      (if expiration
        (wcar (car/setex context-id expiration context))
        (wcar (car/set key context)))
      context-id))
  
  (assoc-context [_ context-id context]
    (if expiration
      (wcar (car/setex context-id expiration context))
      (wcar (car/set key context)))
    nil)
  
  (delete-context [_ context-id]
    (wcar (car/del context-id))
    nil)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; TODO: use Redis watch/multi/exec
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (compare-and-set-context [this context-id old-context new-context]
    (if (= old-context (get-context this context-id))
      (do (assoc-context this context-id new-context)
        true) 
      false))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;; TODO: use Redis watch/multi/exec
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (update-context [_ context-id f]
    (let [context (wcar (car/get context-id))
          ;; what if context-id is not there?
          context (f context)]
      (if expiration
        (wcar (car/setex context-id expiration context))
        (wcar (car/set key context)))
      context))

  IRedisStore
  (reset-conn [_ pool spec]
    (reset! conn-atom {:pool pool :spec spec})))

(defn make-conn-pool
  [& options]
  (if options
    (car/make-conn-pool options)
    (car/make-conn-pool)))

(defn make-conn-spec
  [& options]
  (if options
    (car/make-conn-spec options)
    (car/make-conn-spec)))

(defn redis-store
  "Return empty Redis-based context store. Inspired by Carmine's ring session store."
  [connection-pool connection-spec
   & {:keys [key-prefix expiration-secs]
      :or   {key-prefix       "hs-" ;; "hirop:store:"
             expiration-secs  (* 60 60)}}]
  (RedisStore. (atom {:pool connection-pool :spec connection-spec})
               key-prefix (str expiration-secs)))

(ns re-frame.fx
  (:require
    [re-frame.router  :as router]
    [re-frame.db      :refer [app-db]]
    [re-frame.events  :as events]
    [re-frame.interop :refer [ratom? set-timeout!]]
    [re-frame.loggers :refer [console]]))

;; ---- Spec schema -----------------------------------------------------------
;; TODO use Spec to validate events for :dispatch and :dispatch-n when clj 1.9
;; e.g. (when (= :cljs.spec/invalid (s/conform ::event val))
;;        (console :error (s/explain-str ::event val)))
;; (s/def ::event (s/and vector? (complement empty?)))
;; (s/def ::events-n (s/coll-off ::event))

;; -- Registration ------------------------------------------------------------

(def ^:private id->handler-fn  (atom {}))

(defn lookup-handler
  [effect-id]
  (get @id->handler-fn effect-id))


(defn clear-all-handlers!
  []
  (reset! id->handler-fn {}))


(defn clear-handler!
  [effect-id]
  (if (lookup-handler effect-id)
    (swap! id->handler-fn dissoc effect-id)
    (console :warn "re-frame: unable to clear effect handler for  " effect-id ". Not defined.")))


(defn register
  "register a handler fn for an effect."
  [effect-id handler-fn]
  (when (lookup-handler effect-id)
    (console :warn "re-frame: overwriting an effects handler for: " effect-id))   ;; allow it, but warn.
  (swap! id->handler-fn assoc effect-id handler-fn))


;; -- Standard Builtin Effects Handlers  --------------------------------------

;; Dispatch event(s) after some time.
;; Example:
;; {:dispatch-later [{:ms 200 :dispatch [:event-id "param"]}    ;;  in 200ms do this: (dispatch [:event-id "param"])
;;                   {:ms 100 :dispatch [:also :this :in :100ms]}
;;                   {:ms 250 :dispatch-n (list [:do ] [:all ] [:three ])}]}
;;
(register
  :dispatch-later
  (fn [effects-v]
    ;TODO: use Spec to verify vector and elements when clj 1.9.0 is rel.
    (doseq  [effect effects-v]
      (let [{:keys [ms dispatch dispatch-n]} effect
            tasks (cond dispatch   (list dispatch)
                        dispatch-n dispatch-n
                        :else      (console :error "re-frame: dispatch-later fx must have one of :dispatch or :dispatch-n"))]
        (when (and (some? dispatch) (some? dispatch-n))
          (console :warn "re-frame: dispatch-later fx must have one of :dispatch or :dispatch-n Got: " effect " Ignoring: :dispatch-n"))
        (if (or (empty? tasks) (-> ms number? not))
          (console :warn "re-frame: dispatch-later fx :ms not number or missing :dispatch/:dispatch-n Got:" effect " Ignored.")
          (set-timeout! #(doseq [event tasks]
                          (router/dispatch event)) ms))))))


;; Supply a vector. For example:
;;
;;   {:dispatch [:event-id "param"] }

(register
  :dispatch
  (fn [val]
    (when-not (vector? val)
      (console :warn "re-frame: :dispatch fx val expected vector got:" val))
    (router/dispatch val)))


;; Supply sequencial coll. For example:
;;
;;   {:dispatch-n (list [:do :all] [:three :of] [:these])}
;;
;; Coll can be anything that returns true to sequential? but should not be a map
;; NOTE: this does not include Set
(register
  :dispatch-n
  (fn [val]
    (when (or (-> val sequential? not) (map? val))
      (console :warn "re-frame: :dispatch-n fx val expected sequential not map got:" val))
    (doseq [event val] (router/dispatch event))))


(register
  :deregister-event-handler
  (fn [val]
    (if (sequential? val)
      (doall (map events/clear-handler! val))
      (events/clear-handler! val))))


(register
  :db
  (fn [val]
    (reset! app-db val)))

;; -- Middleware --------------------------------------------------------------

;; XXX a coeffect for jsDate or random number
;; XXX add metadata saying it is fx.
;; XXX ordering
;; XXX think about an undo effect


(defn fx
  [handler]
  (fn fx-handler
    [app-db event-vec]
    (if-not (ratom? app-db)
        (if (map? app-db)
          (console :warn "re-frame: Did you use \"fx\" middleware with \"reg-event\"?  Use \"reg-event-fx\" instead (and don't directly use \"fx\")")
          (console :warn "re-frame: \"fx\" middleware not given a Ratom.  Got: " app-db)))
    (let [run-effect (fn [[key val]]
                       (if-let [effect-fn  (lookup-handler key)]
                         (effect-fn val)
                         (console :error "re-frame: no effects handler registered for: " key ". Ignoring")))
          world   {:db @app-db}]
      (->> (handler world event-vec)   ;; is expected to return a map of effects
           (mapv run-effect)))))       ;; use mapv to process the returned effects (because it isn't lazy)

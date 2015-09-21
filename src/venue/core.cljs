(ns ^:figwheel-always venue.core
    (:require [cljs.core.async :refer [<! chan put! mult tap timeout]]
              [om.core :as om :include-macros true]
              [om-tools.dom :as dom :include-macros true]
              [goog.events :as events]
              [goog.history.EventType :as EventType]
              [secretary.core :as secretary :refer-macros [defroute]])
    (:require-macros [cljs.core.async.macros :as am :refer [go go-loop alt!]]
                     [cljs-log.core :as log])
    (:import goog.History))

;; state blobs
(defonce venue-state (atom {}))
(defonce state (atom {:started? false
                      :service-loop? false
                      :services {}}))

;; channels
(defonce chan-sz 20)
(defonce refresh-ch (chan chan-sz))
(defonce refresh-mult (mult refresh-ch))
(defonce service-request-ch (chan chan-sz))

;; other vars
(defonce history (History.))
(defonce log-prefix "[venue]")
(secretary/set-config! :prefix "#")

;; log prefix helpers
(defn log-debug  [& body] (log/debug  log-prefix " " (apply str (interpose " " body))))
(defn log-info   [& body] (log/info   log-prefix " " (apply str (interpose " " body))))
(defn log-warn   [& body] (log/warn   log-prefix " " (apply str (interpose " " body))))
(defn log-severe [& body] (log/severe log-prefix " " (apply str (interpose " " body))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol IHandleEvent
  (handle-event [this event args cursor]))

(defprotocol IHandleResponse
  (handle-response [this outcome event response cursor]))

(defprotocol IActivate
  (activate [this args cursor]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- fixture-by-id
  [id]
  (->> @venue-state
       (map val)
       (mapcat :fixtures)
       (map (partial apply hash-map))
       (reduce conj)
       id))

(defn- route-list
  []
  (->> @venue-state
       (map val)
       (mapcat :fixtures)
       (map second)
       (map :route)
       set))

(defn- filter-fixtures
  [location opts]
  (let [{:keys [include-static?] :or {include-static? false}} opts]
    (->> @venue-state
         (map val)
         (mapcat :fixtures)
         (map second)
         (filter #(or (and include-static? (:static %)) (= (:route %) location))))))

(defn- get-current-fixture
  [cursor]
  (let [current-id (:current @cursor)]
    (current-id (:fixtures cursor))))

(defn- fixture-by-cursor
  [cursor]
  (->> @venue-state
       (map val)
       (mapcat :fixtures)
       (map second)
       (filter #(= (:state %) @cursor))
       first))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- install-om!
  [target target-element venue-cursor]
  (log-debug target " does not have an om/root. Installing now...")
  (let [event-chan (chan chan-sz)]
    (om/root
     (fn
       [cursor owner]
       (reify
         om/IWillUpdate
         (will-update [_ _ _])
         om/IWillMount
         (will-mount [_]
           (let [refresh-tap (tap refresh-mult (chan chan-sz))]
             ;; loop for refresh
             (go
               (while true
                 (let [_ (<! refresh-tap)]
                   (om/refresh! owner)))))
           ;; FIXME there's something in this go block that upsets the compiler:
           ;; "WARNING: Use of undeclared Var venue.core/bit__16711__auto__"
           (go
             (while true
               (let [e (<! event-chan)]
                 (let [{:keys [view-model state]} (get-current-fixture cursor)
                       vm ((view-model))]
                   (comment (when (satisfies? IHandleEvent vm)
                              (apply (partial handle-event vm) (conj e state)))))))))
         om/IRender
         (render [_]
           (if-let [current-id (:current cursor)]
             (let [{:keys [view state]} (current-id (:fixtures cursor))]
               (dom/div nil
                        (if view
                          (om/build (view) state))))))))
     venue-state
     {:target target-element
      :path [target]
      :shared {:event-chan event-chan}}))
  (om/update! venue-cursor [target :installed?] true))

(defn- launch-route!
  [location route-params]
  (let [venue-cursor (om/root-cursor venue-state)]
    ;; loop routes applicable to this location
    (doseq [{:keys [target id]} (filter-fixtures location {:include-static? true})]
      (if-let [target-element (. js/document (getElementById (name target)))]
        (do
          ;; if we're not installed, add an om/root
          (when (not (:installed? (get venue-cursor target)))
            (install-om! target target-element venue-cursor))

          ;; activate vm
          (let [{:keys [view-model]} (fixture-by-id id)
                vm ((view-model))]
            (when (satisfies? IActivate vm)
              (activate vm route-params (-> venue-cursor target :fixtures id :state))))

          ;; write current id to state
          (om/update! venue-cursor [target :current] id))

        (log-warn target " couldn't be found. Use <TODO> to suppress the warning.")))))

(defn- add-view!
  [{:keys [target view view-model id state route] :as fix}]

  ;; FIXME perhaps we do something other than throw here?
  (if (fixture-by-id id)
    (throw (js/Error. (str "A route with id " id " already exists!"))))

  ;; check for new routes.
  (when (not (contains? (route-list) route))
    (log-debug "Defining a route for " route)
    (defroute (str route) {:as params}
      (log-info "Routing " route)
      (launch-route! route params)))

  ;; save the view
  (let [ktarget (keyword target)
        mfix (-> fix
                 (assoc :target ktarget)
                 (assoc :static false))]
    (swap! venue-state assoc-in [ktarget :fixtures id] mfix)))

(defn- add-static-view!
  [{:keys [target id] :as fixture}]
  (let [ktarget (keyword target)]
    (swap! venue-state assoc-in [ktarget :fixtures id] (-> fixture
                                                           (assoc :target ktarget)
                                                           (assoc :static true)))))

(defn- start-service-loop!
  []
  (go-loop [[caller cursor service-id req-id args] (<! service-request-ch)]
    (if-let [service (get-in @state [:services service-id])]
      (let [c (chan)
            to (timeout 5000)
            rfn (fn [caller outcome req-id data cursor]
                  (when (satisfies? IHandleResponse caller)
                    (handle-response caller outcome req-id data cursor)))]
        (log-debug "Received service request:" service-id req-id)
        (go
          (alt!
            c  ([[outcome data]] (rfn caller outcome req-id data cursor))
            to ([_] (rfn caller :failure req-id "The service request timed out" cursor))))
        (service req-id args c))
      (log-severe "A request was sent to an unknown service: " service-id))
    (recur (<! service-request-ch))))

(defn- add-service!
  [{:keys [id handler]}]
  (swap! state assoc-in [:services id] handler)

  (when (not (:service-loop? @state))
    (swap! state assoc :service-loop? true)
    (start-service-loop!)))

(defn raise!
  ([owner event]
   (raise! owner event {}))
  ([owner event args]
   (let [c (om/get-shared owner [:event-chan])]
     (put! c [event args]))))

(defn request!
  ([cursor service id]
   (request! cursor service id {}))
  ([cursor service id args]
   (let [{:keys [view-model]} (fixture-by-cursor cursor)
         vm ((view-model))]
     (put! service-request-ch [vm cursor service id args]))))

(defn get-route
  ([id]
   (get-route id {}))
  ([id opts]
   (if-let [route (->> id fixture-by-id :route)]
     (secretary/render-route route opts)
     (log-severe "get-route tried failed to find a fixture with the following id:" id))))

(defn navigate!
  ([id]
   (navigate! id {}))
  ([id opts]
   (let [route (get-route id opts)]
     (if (:static route)
       (throw (js/Error. "Cannot navigate to a static fixture!")))
     (log-info "Navigating to " route)
     (set! (.. js/document -location -href) route))))

(defn start!
  []
  (when (not (:started? @state))
    (swap! state assoc :started? true)
    (log-info "Starting up...")
    (launch-route! nil nil) ;; install statics
    (secretary/dispatch! js/window.location.hash)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ROUTING FUNCTIONS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn on-navigate [event]
  (let [path (.-token event)]
    (secretary/dispatch! path)))

(defonce set-up-history!
  (doto history
    (goog.events/listen EventType/NAVIGATE on-navigate)
    (.setEnabled true)))

(defn on-js-reload []
  (log-debug "Refreshing views...")
  (put! refresh-ch true))

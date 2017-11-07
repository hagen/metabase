(ns metabase.api.alert
  "/api/alert endpoints"
  (:require [clojure.data :as data]
            [compojure.core :refer [DELETE GET POST PUT]]
            [hiccup.core :refer [html]]
            [medley.core :as m]
            [metabase
             [driver :as driver]
             [email :as email]
             [events :as events]
             [pulse :as p]
             [query-processor :as qp]
             [util :as u]]
            [metabase.api
             [common :as api]
             [pulse :as pulse-api]]
            [metabase.email.messages :as messages]
            [metabase.integrations.slack :as slack]
            [metabase.models
             [card :refer [Card]]
             [interface :as mi]
             [pulse :as pulse :refer [Pulse]]
             [pulse-channel :refer [channel-types]]]
            [metabase.pulse.render :as render]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db])
  (:import java.io.ByteArrayInputStream
           java.util.TimeZone))

(api/defendpoint GET "/"
  "Fetch all `Alerts`"
  []
  (for [alert (pulse/retrieve-alerts)
        :let  [can-read?  (mi/can-read? alert)
               can-write? (mi/can-write? alert)]
        :when (or can-read?
                  can-write?)]
    (assoc alert :read_only (not can-write?))))

(api/defendpoint GET "/question/:id"
  [id]
  (for [alert (if api/*is-superuser?*
                (pulse/retrieve-alerts-for-card id)
                (pulse/retrieve-user-alerts-for-card id api/*current-user-id*))
        :let  [can-read?  (mi/can-read? alert)
               can-write? (mi/can-write? alert)]]
    (assoc alert :read_only (not can-write?))))

(def ^:private AlertConditions
  (s/enum "rows" "goal"))

(defn- only-alert-keys [request]
  (select-keys request [:alert_condition :alert_first_only :alert_above_goal]))

(api/defendpoint POST "/"
  "Create a new `Alert`."
  [:as {{:keys [alert_condition card channels alert_first_only alert_above_goal] :as req} :body}]
  {alert_condition   AlertConditions
   alert_first_only  s/Bool
   alert_above_goal  (s/maybe s/Bool)
   card              su/Map
   channels          (su/non-empty [su/Map])}
  (pulse-api/check-card-read-permissions [card])
  (let [new-alert (api/check-500
                   (-> req
                       only-alert-keys
                       (pulse/create-alert! api/*current-user-id* (u/get-id card) channels)))]
    (when (email/email-configured?)
      (messages/send-new-alert-email! new-alert))

    new-alert))

(defn- recipient-ids [{:keys [channels] :as alert}]
  (reduce (fn [acc {:keys [channel_type recipients]}]
            (if (= :email channel_type)
              (into acc (map :id recipients))
              acc))
          #{} channels))

(defn- check-alert-update-permissions
  "Admin users can update all alerts. Non-admin users can update alerts that they created as long as they are still a
  recipient of that alert"
  [alert]
  (when-not api/*is-superuser?*
    (api/write-check alert)
    (api/check-403 (and (= api/*current-user-id* (:creator_id alert))
                        (contains? (recipient-ids alert) api/*current-user-id*)))))

(defn- email-channel [alert]
  (m/find-first #(= :email (:channel_type %)) (:channels alert)))

(defn- slack-channel [alert]
  (m/find-first #(= :slack (:channel_type %)) (:channels alert)))

(defn- notify-recipient-changes! [old-alert updated-alert]
  (let [{old-recipients :recipients} (email-channel old-alert)
        {new-recipients :recipients} (email-channel updated-alert)
        old-ids->users (zipmap (map :id old-recipients)
                               old-recipients)
        new-ids->users (zipmap (map :id new-recipients)
                               new-recipients)
        [removed-ids added-ids _] (data/diff (set (keys old-ids->users))
                                             (set (keys new-ids->users)))]

    (doseq [old-id removed-ids
            :let [removed-user (get old-ids->users old-id)]]
      (messages/send-admin-unsubscribed-alert-email! old-alert removed-user @api/*current-user*))

    (doseq [new-id added-ids
            :let [added-user (get new-ids->users new-id)]]
      (messages/send-you-were-added-alert-email! updated-alert added-user @api/*current-user*))))

(api/defendpoint PUT "/:id"
  "Update a `Alert` with ID."
  [id :as {{:keys [alert_condition card channels alert_first_only alert_above_goal card channels] :as req} :body}]
  {alert_condition  AlertConditions
   alert_first_only s/Bool
   alert_above_goal (s/maybe s/Bool)
   card             su/Map
   channels         (su/non-empty [su/Map])}
  (let [old-alert     (pulse/retrieve-alert id)
        _             (check-alert-update-permissions old-alert)
        updated-alert (-> req
                          only-alert-keys
                          (assoc :id id :card (u/get-id card) :channels channels)
                          pulse/update-alert!)]

    (when (and api/*is-superuser?* (email/email-configured?))
      (notify-recipient-changes! old-alert updated-alert))

    updated-alert))

(defn- should-unsubscribe-delete?
  "An alert should be deleted instead of unsubscribing if
     - the unsubscriber is the creator
     - they are the only recipient
     - there is no slack channel selected"
  [alert unsubscribing-user-id]
  (let [{:keys [recipients]} (email-channel alert)]
    (and (= unsubscribing-user-id (:creator_id alert))
         (= 1 (count recipients))
         (= unsubscribing-user-id (:id (first recipients)))
         (nil? (slack-channel alert)))))

(api/defendpoint PUT "/:id/unsubscribe"
  [id]
  ;; Admins are not allowed to unsubscribe from alerts, they should edit the alert
  (api/check (not api/*is-superuser?*)
    [400 "Admin user are not allowed to unsubscribe from alerts"])
  (assert (integer? id))
  (let [alert (pulse/retrieve-alert id)]
    (api/read-check alert)

    (if (should-unsubscribe-delete? alert api/*current-user-id*)
      (db/delete! Pulse :id id)
      (pulse/unsubscribe-from-alert id api/*current-user-id*))

    (when (email/email-configured?)
      (messages/send-you-unsubscribed-alert-email! alert @api/*current-user*))

    api/generic-204-no-content))

(api/defendpoint DELETE "/:id"
  [id]
  (api/let-404 [pulse (pulse/retrieve-alert id)]
    (api/check-403
     (or (= api/*current-user-id* (:creator_id pulse))
          api/*is-superuser?*))
    (db/delete! Pulse :id id)
    api/generic-204-no-content))

(api/define-routes)

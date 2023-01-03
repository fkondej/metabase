(ns metabase.api.segment
  "/api/segment endpoints."
  (:require
   [clojure.tools.logging :as log]
   [compojure.core :refer [DELETE GET POST PUT]]
   [metabase.api.common :as api]
   [metabase.api.query-description :as api.qd]
   [metabase.events :as events]
   [metabase.mbql.normalize :as mbql.normalize]
   [metabase.models.interface :as mi]
   [metabase.models.revision :as revision]
   [metabase.models.segment :as segment :refer [Segment]]
   [metabase.models.table :as table :refer [Table]]
   [metabase.related :as related]
   [metabase.util :as u]
   [metabase.util.i18n :refer [trs]]
   [metabase.util.schema :as su]
   [schema.core :as s]
   [toucan.db :as db]
   [toucan.hydrate :refer [hydrate]]))

(api/defendpoint-schema POST "/"
  "Create a new `Segment`."
  [:as {{:keys [name description table_id definition], :as body} :body}]
  {name       su/NonBlankStringPlumatic
   table_id   su/IntGreaterThanZeroPlumatic
   definition su/MapPlumatic
   description (s/maybe s/Str)}
  ;; TODO - why can't we set other properties like `show_in_getting_started` when we create the Segment?
  (api/create-check Segment body)
  (let [segment (api/check-500
                 (db/insert! Segment
                   :table_id    table_id
                   :creator_id  api/*current-user-id*
                   :name        name
                   :description description
                   :definition  definition))]
    (-> (events/publish-event! :segment-create segment)
        (hydrate :creator))))

(s/defn ^:private hydrated-segment [id :- su/IntGreaterThanZeroPlumatic]
  (-> (api/read-check (db/select-one Segment :id id))
      (hydrate :creator)))

(defn- add-query-descriptions
  [segments] {:pre [(coll? segments)]}
  (when (some? segments)
    (for [segment segments]
      (let [table (db/select-one Table :id (:table_id segment))]
        (assoc segment
               :query_description
               (api.qd/generate-query-description table (:definition segment)))))))

(api/defendpoint-schema GET "/:id"
  "Fetch `Segment` with ID."
  [id]
  (first (add-query-descriptions [(hydrated-segment id)])))

(api/defendpoint-schema GET "/"
  "Fetch *all* `Segments`."
  []
  (as-> (db/select Segment, :archived false, {:order-by [[:%lower.name :asc]]}) segments
    (filter mi/can-read? segments)
    (hydrate segments :creator)
    (add-query-descriptions segments)))

(defn- write-check-and-update-segment!
  "Check whether current user has write permissions, then update Segment with values in `body`. Publishes appropriate
  event and returns updated/hydrated Segment."
  [id {:keys [revision_message], :as body}]
  (let [existing   (api/write-check Segment id)
        clean-body (u/select-keys-when body
                     :present #{:description :caveats :points_of_interest}
                     :non-nil #{:archived :definition :name :show_in_getting_started})
        new-def    (->> clean-body :definition (mbql.normalize/normalize-fragment []))
        new-body   (merge
                     (dissoc clean-body :revision_message)
                     (when new-def {:definition new-def}))
        changes    (when-not (= new-body existing)
                     new-body)
        archive?   (:archived changes)]
    (when changes
      (db/update! Segment id changes))
    (u/prog1 (hydrated-segment id)
      (events/publish-event! (if archive? :segment-delete :segment-update)
        (assoc <> :actor_id api/*current-user-id*, :revision_message revision_message)))))

(api/defendpoint-schema PUT "/:id"
  "Update a `Segment` with ID."
  [id :as {{:keys [name definition revision_message archived caveats description points_of_interest
                   show_in_getting_started]
            :as   body} :body}]
  {name                    (s/maybe su/NonBlankStringPlumatic)
   definition              (s/maybe su/MapPlumatic)
   revision_message        su/NonBlankStringPlumatic
   archived                (s/maybe s/Bool)
   caveats                 (s/maybe s/Str)
   description             (s/maybe s/Str)
   points_of_interest      (s/maybe s/Str)
   show_in_getting_started (s/maybe s/Bool)}
  (write-check-and-update-segment! id body))

(api/defendpoint-schema DELETE "/:id"
  "Archive a Segment. (DEPRECATED -- Just pass updated value of `:archived` to the `PUT` endpoint instead.)"
  [id revision_message]
  {revision_message su/NonBlankStringPlumatic}
  (log/warn
   (trs "DELETE /api/segment/:id is deprecated. Instead, change its `archived` value via PUT /api/segment/:id."))
  (write-check-and-update-segment! id {:archived true, :revision_message revision_message})
  api/generic-204-no-content)


(api/defendpoint-schema GET "/:id/revisions"
  "Fetch `Revisions` for `Segment` with ID."
  [id]
  (api/read-check Segment id)
  (revision/revisions+details Segment id))


(api/defendpoint-schema POST "/:id/revert"
  "Revert a `Segement` to a prior `Revision`."
  [id :as {{:keys [revision_id]} :body}]
  {revision_id su/IntGreaterThanZeroPlumatic}
  (api/write-check Segment id)
  (revision/revert!
    :entity      Segment
    :id          id
    :user-id     api/*current-user-id*
    :revision-id revision_id))

(api/defendpoint-schema GET "/:id/related"
  "Return related entities."
  [id]
  (-> (db/select-one Segment :id id) api/read-check related/related))

(api/define-routes)

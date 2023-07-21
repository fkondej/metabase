(ns metabase.search.filter
  "Namespace that defines the filters that are applied to the search results.

  There are required filters and optional filters.
  Archived is an required filters and is always applied, the reason because by default we want to hide archived/inactive entities.

  But there are OPTIONAL FILTERS like :created-by, :created-at, when these filters are provided, the results will return only
  results of models that have these filters.

  The multi method for optional filters should have the default implementation to throw for unsupported models, and then each model
  that supports the filter should define its own method for the filter."
  (:require
   [clojure.set :as set]
   [metabase.search.config :as search.config :refer [SearchableModel SearchContext]]
   [metabase.util.malli :as mu]))

(def ^:private true-clause [:inline [:= 1 1]])
(def ^:private false-clause [:inline [:= 0 1]])

;; ------------------------------------------------------------------------------------------------;;
;;                                            Helper fns                                           ;;
;; ------------------------------------------------------------------------------------------------;;

(def ^:private feature->supported-models
  {:created-by #{"action" "card" "dataset" "dashboard"}})

(defn- has-optional-filters?
  [{:keys [created-by] :as _search-context}]
  (boolean (some some? [created-by])))

;; ------------------------------------------------------------------------------------------------;;
;;                                         Required Filters                                         ;
;; ------------------------------------------------------------------------------------------------;;

(defmulti ^:private archived-clause
  "Clause to filter by the archived status of the entity."
  {:arglists '([model archived?])}
  (fn [model _] model))

(defmethod archived-clause :default
  [model archived?]
  [:= (search.config/column-with-model-alias model :archived) archived?])

;; Databases can't be archived
(defmethod archived-clause "database"
  [_model archived?]
  (if archived?
    false-clause
    true-clause))

(defmethod archived-clause "indexed-entity"
  [_model archived?]
  (if-not archived?
    true-clause
    false-clause))

;; Table has an `:active` flag, but no `:archived` flag; never return inactive Tables
(defmethod archived-clause "table"
  [model archived?]
  (if archived?
    false-clause                        ; No tables should appear in archive searches
    [:and
     [:= (search.config/column-with-model-alias model :active) true]
     [:= (search.config/column-with-model-alias model :visibility_type) nil]]))

;; ------------------------------------------------------------------------------------------------;;
;;                                         Optional filters                                        ;;
;; ------------------------------------------------------------------------------------------------;;

(defmulti ^:private created-by-clause
  "Clause to filter by the creator of the entity."
  {:arglists '([model creator-id])}
  (fn [model _creator-id]
    model))

(defmethod created-by-clause :default
  [model _creator-id]
  (throw (ex-info (format "Created by filter for %s is not supported" model) {:model model})))

(defmethod created-by-clause "card"
  [model creator-id]
  [:= (search.config/column-with-model-alias model :creator_id) creator-id])

(defmethod created-by-clause "dataset"
  [model creator-id]
  [:= (search.config/column-with-model-alias model :creator_id) creator-id])

(defmethod created-by-clause "dashboard"
  [model creator-id]
  [:= (search.config/column-with-model-alias model :creator_id) creator-id])

(defmethod created-by-clause "action"
  [model creator-id]
  [:= (search.config/column-with-model-alias model :creator_id) creator-id])

;; ------------------------------------------------------------------------------------------------;;
;;                                        Public functions                                         ;;
;; ------------------------------------------------------------------------------------------------;;

(mu/defn search-context->applicable-models :- [:set SearchableModel]
  "Given a search-context, retuns the list of models that can be applied to it.

  If the context has optional filters, the models will be restricted for the set of supported models only."
  [{:keys [created-by models] :as search-context} :- SearchContext]
  (if-not (has-optional-filters? search-context)
    models
    (cond-> #{}
      (some? created-by) (set/union (:created-by feature->supported-models))

      true               (set/intersection models))))

(defn- build-optional-filters
  [model {:keys [created-by] :as _search-context}]
  (cond-> []
    (int? created-by) (conj (created-by-clause model created-by))))

(mu/defn build-filters
  "Build the search filters for a model."
  [model          :- SearchableModel
   search-context :- SearchContext]
  (let [{:keys [archived?]} search-context
        archived-filter   (archived-clause model archived?)
        optional-filters  (build-optional-filters model search-context)]
    (filter seq (conj optional-filters archived-filter))))

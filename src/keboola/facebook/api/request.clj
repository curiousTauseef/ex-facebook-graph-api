(ns keboola.facebook.api.request
  (:require   [clojure.spec :as s]
              [keboola.facebook.api.parser :as parser]
              [keboola.docker.runtime :refer [log-strings]]
              [keboola.http.client :as client]
              [clojure.string :as string]))

(def graph-api-url "https://graph.facebook.com/")
(def default-version "v2.8")

(s/fdef make-url
        :args (s/or :path-only (s/cat :path string? )
                    :path-and-version (s/cat :path string? :version string?))
        :fn #(-> % :ret (clojure.string/starts-with? graph-api-url))
        :ret string?)

(defn make-url
  "return absolute url to fb api given relative @path and @version"
  ([path] (make-url path default-version))
  ([path version]
   (str graph-api-url version "/" path)))

(defn extract-values
  "traverse object(@row) values and take only scalar values or flatten simple objects(key->value) or explodes arrays(insights metrics)
  return list of objects with enhanced info(:keboola keyword) and all scalar values"
  [row params account-id]
  (let [scalars (parser/filter-scalars row)
        objects-flatten (parser/filter-flatten-objects row )
        all-simple-scalars (merge {:account-id account-id :keboola params} scalars objects-flatten)
        arrays (filter (fn [[k v]] (vector? v)) row)
        merged-arrays (map
                       #(merge all-simple-scalars %) (mapcat (fn [[_ array]]
                                                               (parser/flatten-array array)) arrays))]
    (if (empty? merged-arrays)
      (list all-simple-scalars)
      merged-arrays)))

(defn get-next-page-url
  "return url to the next page from @response param"
  [response]
  (get-in response [:paging :next]))

(defn get-next-page-data
  "if response contains next page url then call it and wait for new repsonse
  result: vector with new nested-object like structure
  "
  [response params]
  ;(println "next url" (get-next-page-url response) (:paging response))
  (if-let [next-page-url (get-next-page-url response )] ; process next api page if exists
    (let [new-response (:body ((:api-fn params) next-page-url))]
      [{
        :parent-id (:parent-id params)
        :parent-type (:parent-type params)
        :name (:table-name params)
        :data new-response
        }])))

(defn page-and-collect
  "collect data from response and make another paging requests if needed.
  Returns lazy sequence of flattened data resulting from processing the whole query"
  [{:keys [account-id parent-id parent-type table-name body-data api-fn response] :as init-params} ]
  ((fn step [params this-object-data rest-objects]
            (if (and (empty? rest-objects) (empty? this-object-data))
              nil
              (let [
                    new-rows (mapcat #(extract-values % (dissoc params :body-data :response :api-fn) account-id) this-object-data)

                    next-page-data (get-next-page-data (:response params) params)
                    nested-objects (concat (parser/get-nested-objects this-object-data params) next-page-data)
                    all-objects (concat nested-objects rest-objects)
                    next-object (first all-objects)
                    new-params (assoc params
                                      :parent-id (:parent-id next-object)
                                      :parent-type (:parent-type next-object)
                                      :table-name (:name next-object)
                                      :response (:data next-object)
                                      :body-data (:data (:data next-object)))]
                (lazy-seq (cons new-rows (step new-params (:body-data new-params) (rest all-objects) )))))) init-params body-data [] ))

(defn make-paging-fn [access-token]
  (fn [url] (client/GET url :query-params {:access_token access-token} :as :json)))

(defn nested-request
  "Make a initial request to fb api given query and collect its result data.
  Returns collection of maps of key-value pairs page-id -> result_data "
  [access-token {:keys [fields ids path limit]} & {:keys [ version]}]
  (let [preparsed-fields (parser/preparse-fields fields)
        query-params {:access_token access-token :fields preparsed-fields :ids ids :limit limit}
        full-url (make-url path version)
        request-fn (fn [url] (client/GET url :query-params query-params :as :json))
        response (request-fn full-url)
        next-page-api-fn (make-paging-fn access-token)
        ]
    (log-strings "calling" full-url "with" preparsed-fields ids)
    (mapcat
     #(page-and-collect
       {
        :account-id (name (first %))
        :parent-id (name (first %))
        :parent-type "page"
        :table-name "page"
        :body-data [(if (not-empty path) {(keyword path) (second %)} (second %))]
        :response (:body response)
        :api-fn next-page-api-fn})
     (:body response))))

(defn get-accounts [access-token & {:keys [version]}]
  (get-in (client/GET (make-url "me/accounts" version)
                      :query-params {:access_token access-token} :as :json)
          [:body :data]))

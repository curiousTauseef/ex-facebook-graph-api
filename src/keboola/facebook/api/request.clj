(ns keboola.facebook.api.request
  (:require [keboola.http.client :as client])
  )

(def graph-api-url "https://graph.facebook.com/")
(def default-version "v2.8")

(defn get-next-page-url [response]
  (get-in response [:paging :next]))

(defn get-response-data [response]
  (get-in response [:data])
  )

(defn collect-result [response api-fn]
  ;(println (keys response) (:body (api-fn (get-next-page-url response))))
  (lazy-seq
   (if (get-next-page-url response)
     (concat (get-response-data response)
             (collect-result
              (:body (api-fn (get-next-page-url response)))
              api-fn
              )))))


(defn make-url [path & {:keys [version]}]
  (str graph-api-url (or version default-version) "/" path))


(defn get-request [access-token path & {:keys [query version]}]
  (let [query-params (assoc query :access_token access-token)
        request-fn (fn [url] (client/GET url :query-params query-params :as :json))]
      (collect-result
       (request-fn (make-url path :version version))
       request-fn
       )))

(defn nested-request [access-token path & {:keys [query version]}]
  (let [query-params (assoc query :access_token access-token :method "GET")
        full-url (make-url path :version version)
        request-fn (fn [url] (client/POST url :form-params query-params :as :json))
        response (request-fn full-url)
        ]
    ;(println (map #(first %) (:body response)))
    (map
     #(hash-map
       :account-id (first %)
       :data (collect-result
              (-> % second :posts)
              (fn [url] (client/GET url :query-params {:access_token access-token} :as :json))
               ))
     (:body response))))

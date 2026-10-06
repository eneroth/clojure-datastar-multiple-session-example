(ns example.core
  "HTTP: the page, its stream, and actions.

  - `GET /`             creates a tab session and server-side renders its first frame.
  - `GET /stream?tab=`  attaches the tab's SSE connection to its session (CQRS: queries).
                        `&load=` names the page load, so a copy of the page is told apart.
  - `POST /act/:token`  invokes a mounted action (CQRS: commands). The response is
                        204, or JSON signals; view updates arrive over the stream."
  (:require
    [charred.api :as json]
    [example.action :as action]
    [example.page :as page]
    [example.session :as session]
    [example.state :as state]
    [reitit.ring :as rr]
    [ring.middleware.cookies :refer [wrap-cookies]]
    [ring.middleware.params :refer [wrap-params]]
    [ring.util.response :as resp]
    [starfederation.datastar.clojure.adapter.ring :refer [->sse-response on-open on-close]]
    [starfederation.datastar.clojure.api :as d*]))


(def ^:private uid-cookie "uid")


(defn- request-uid
  [req]
  (get-in req [:cookies uid-cookie :value]))


(defn home
  [req respond _raise]
  (let [uid     (or (request-uid req) (str (random-uuid)))
        tab     (str (random-uuid))
        _       (state/ensure-user! uid)
        session (session/create! tab uid page/root)
        frame   (session/render-page! session)]
    ;; No first frame in time: drop the session. The stream will find the tab
    ;; unknown and resync a fresh session from scratch.
    (when-not frame
      (session/close! session))
    (respond
      (-> (resp/response (page/shell tab frame))
        (resp/content-type "text/html; charset=utf-8")
        ;; The page carries its tab id. Kept out of the HTTP cache, a duplicated
        ;; or restored tab fetches its own instead of sharing this one.
        (resp/header "Cache-Control" "no-store")
        (resp/set-cookie uid-cookie uid {:path "/" :http-only true :same-site :lax})))))


(defn stream
  [req respond _raise]
  (let [uid     (request-uid req)
        tab     (get-in req [:query-params "tab"])
        load    (get-in req [:query-params "load"])
        session (when (and uid (parse-uuid (str tab)))
                  (state/ensure-user! uid)
                  (session/obtain! tab uid page/root))]
    (respond
      (if session
        (->sse-response req {on-open  #(session/attach! session % load)
                             on-close #(session/detach! session %)})
        {:status 403 :body "Not your tab."}))))


(defn- read-signals
  [req]
  (try
    (json/read-json (d*/get-signals req))
    (catch Exception _ {})))


(defn act
  [req respond _raise]
  (let [token           (get-in req [:path-params :token])
        [status result] (action/invoke! token (request-uid req) (read-signals req))]
    (respond
      (case status
        :ok        (if (map? result)
                     (-> (resp/response (json/write-json-str result))
                       (resp/content-type "application/json"))
                     {:status 204})
        :forbidden {:status 403 :body "Not your action."}
        :gone      {:status 404 :body "No such action: never minted, or its island has unmounted."}))))


(def routes
  [["/" {:get home}]
   ["/stream" {:get stream}]
   ["/act/:token" {:post act}]])


(def handler
  (rr/ring-handler
    (rr/router routes)
    (rr/create-default-handler)
    {:middleware [wrap-cookies wrap-params]}))

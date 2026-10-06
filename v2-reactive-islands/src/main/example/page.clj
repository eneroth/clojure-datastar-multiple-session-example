(ns example.page
  "The demo UI: one root island per session, built from the session's context.

  Every island is constructed per session, so render counts are per tab. Each
  one demonstrates one claim from the README:

  - `feed`:       shared resources, switching, and the linger.
  - `vault-card`: scope-based ACL. Revoking unmounts the panel, releasing its
                  resource and revoking its actions.
  - `resources`:  the registry, live: what is open, for whom, and what lingers.
  - `bulk`:       a big static island. It is sent on full resyncs and never patched."
  (:require
    [dev.onionpancakes.chassis.core :as h]
    [example.action :as action :refer [action]]
    [example.island :as island :refer [island slot]]
    [example.resource :as resource]
    [example.session :as session]
    [example.state :as state]
    [missionary.core :as m]
    [starfederation.datastar.clojure.api :as d*]))


(def topics
  {"alpha" {:connect-ms 300 :period-ms 1000}
   "beta"  {:connect-ms 600 :period-ms 400}
   "gamma" {:connect-ms 900 :period-ms 2500}})


(def topic-names (vec (sort (keys topics))))


(def ^:private card
  [:rounded-xl :border :border-zinc-200 :bg-white :p-5 :shadow-sm :space-y-4
   :dark:border-zinc-800 :dark:bg-zinc-900])


(def ^:private muted
  [:text-sm :text-zinc-500 :dark:text-zinc-400])


(def ^:private button
  [:rounded-md :border :border-zinc-300 :px-3 :py-1 :text-sm :font-medium :cursor-pointer
   :hover:bg-zinc-100 :dark:border-zinc-700 :dark:hover:bg-zinc-800])


(def ^:private button-active
  [:rounded-md :border :border-indigo-600 :bg-indigo-600 :px-3 :py-1 :text-sm :font-medium :text-white])


(defn- heading
  [title & body]
  [:div
   [:h2 {:class [:text-lg :font-semibold]} title]
   (into [:p {:class muted}] body)])


(defn- reading-view
  [v]
  (if (= ::resource/pending v)
    [:p {:class (conj muted :h-16 :animate-pulse)} "Connecting…"]
    [:div {:class [:h-16]}
     [:div {:class [:font-mono :text-4xl :tabular-nums]} (:reading v)]
     [:p {:class muted} "reading #" (:n v) " at " (:at v)]]))


;; Header -----------------------------------------------------------------------


(defn- header
  [{:keys [tab uid]}]
  (island "header" {}
    (fn [_]
      [:header {:class [:space-y-1]}
       [:h1 {:class [:text-2xl :font-bold]} "Reactive islands"]
       [:p {:class muted}
        "Tab " [:code (subs tab 0 8)] " · user " [:code (subs uid 0 8)]
        " · Open this page in several tabs, switch topics, revoke access, and hide a tab for "
        (quot session/grace-ms 1000) " s."]])))


;; Shared feed ------------------------------------------------------------------


(defn- ticker
  "Mounted per topic by `switch-by`, so switching topics unmounts the previous
  ticker and releases its subscription."
  [topic]
  (island "ticker"
    {:inputs {:v (resource/subscribe [:feed topic] (resource/ticker (topics topic)))}}
    (fn [{:keys [v]}]
      (reading-view v))))


(defn- feed-card
  [{:keys [uid]} !topic]
  (island "feed"
    {:tag    :section
     :attrs  {:class card}
     :inputs {:topic  (m/watch !topic)
              :select (island/collect
                        (into {}
                          (map (fn [t] [t (action uid (fn [_] (reset! !topic t) nil))]))
                          topic-names))}
     :slots  {:ticker (island/switch-by (m/watch !topic) ticker)}}
    (fn [{:keys [topic select]}]
      [:div {:class [:space-y-4]}
       (heading "Shared feed"
         "Tabs on the same topic share one resource. Switching unmounts the ticker; "
         "the old resource lingers " (quot resource/linger-ms 1000) " s, then closes.")
       [:div {:class [:flex :gap-2]}
        (for [t topic-names]
          [:button {:class         (if (= t topic) button-active button)
                    :data-on:click (select t)}
           t])]
       (slot :ticker)])))


;; Restricted vault -------------------------------------------------------------


(defn- vault-feed
  []
  (island "vault-feed"
    {:inputs {:v (resource/subscribe [:vault] (resource/ticker {:connect-ms 800 :period-ms 1500}))}}
    (fn [{:keys [v]}]
      (reading-view v))))


(defn- messages
  []
  (island "messages"
    {:tag    :ul
     :attrs  {:class [:space-y-1 :text-sm]}
     :inputs {:messages (m/watch state/!messages)}}
    (fn [{:keys [messages]}]
      (if (empty? messages)
        [:li {:class muted} "No messages yet."]
        (for [{:keys [uid text]} (rseq messages)]
          [:li [:code {:class muted} (subs uid 0 8)] " " text])))))


(defn- composer
  "Holds the input, and depends on nothing that ticks, so typing is never morphed away."
  [uid]
  (island "composer"
    {:inputs {:post (action uid (fn [signals]
                                  (when (state/post-message! uid (get signals "note"))
                                    {:note ""})))}}
    (fn [{:keys [post]}]
      [:div {:class [:flex :gap-2]}
       [:input {:data-bind       "note"
                :data-on:keydown (str "evt.key === 'Enter' && " post)
                :placeholder     "Message the other permitted viewers"
                :class           [:flex-1 :rounded-md :border :border-zinc-300 :bg-transparent :px-2 :py-1
                                  :text-sm :dark:border-zinc-700]}]
       [:button {:class button :data-on:click post} "Post"]])))


(defn- vault-panel
  [uid]
  (island "vault"
    {:attrs {:class [:space-y-4]}
     :slots {:feed     (vault-feed)
             :messages (messages)
             :composer (composer uid)}}
    (fn [_]
      [:div {:class [:space-y-4]}
       (slot :feed)
       (slot :messages)
       (slot :composer)])))


(defn- no-access
  []
  (island "vault" {}
    (fn [_]
      [:p {:class (conj muted :h-16)} "No access. Nothing under the gate is mounted: no resource, no actions."])))


(defn- vault-card
  [{:keys [uid]}]
  (let [allowed (m/latest #(state/allowed? % uid :vault) (m/watch state/!acl))]
    (island "vault-card"
      {:tag    :section
       :attrs  {:class card}
       :inputs {:allowed allowed
                :toggle  (action uid (fn [_] (state/toggle! uid :vault) nil))}
       :slots  {:vault (island/gate allowed (vault-panel uid) (no-access))}}
      (fn [{:keys [allowed toggle]}]
        [:div {:class [:space-y-4]}
         [:div {:class [:flex :items-start :justify-between :gap-4]}
          (heading "Restricted"
            "Gated on your ACL, which is server state; every tab of yours follows it. "
            "Revoking unmounts the panel: its resource is released and its actions stop existing.")
          [:button {:class button :data-on:click toggle}
           (if allowed "Revoke" "Grant")]]
         (slot :vault)]))))


;; Server resources -------------------------------------------------------------


(def ^:private cell [:py-1 :pr-4])


(defn- resources-card
  []
  (island "resources"
    {:tag    :section
     :attrs  {:class card}
     :inputs {:entries  (m/latest resource/summary (m/watch resource/!entries))
              :totals   (m/watch resource/!totals)
              :sessions (m/latest session/summary (m/watch session/!sessions))
              :actions  (m/latest count (m/watch action/!actions))}}
    (fn [{:keys [entries totals sessions actions]}]
      [:div {:class [:space-y-4]}
       (heading "Server resources"
         "The registry, live, across all sessions. Opened " (:opened totals)
         ", closed " (:closed totals) ". Sessions: " (:attached sessions) " attached, "
         (:detached sessions) " detached (in grace). Live action tokens: " actions ".")
       [:table {:class [:w-full :text-left :text-sm]}
        [:thead
         [:tr {:class muted}
          [:th {:class cell} "Resource"] [:th {:class cell} "State"] [:th {:class cell} "Subscribers"]]]
        [:tbody {:class [:font-mono]}
         (if (empty? entries)
           [:tr [:td {:class (conj cell :text-zinc-500) :colspan 3} "None open."]]
           (for [[k {:keys [state subscribers]}] entries]
             [:tr
              [:td {:class cell} (pr-str k)]
              [:td {:class (conj cell (case state
                                        :live :text-emerald-600
                                        :connecting :text-amber-600
                                        :lingering :text-zinc-400))}
               (name state)]
              [:td {:class cell} subscribers]]))]]])))


;; Static bulk ------------------------------------------------------------------


(defn- bulk
  []
  (island "bulk"
    {:tag   :section
     :attrs {:class card}}
    (fn [_]
      [:details
       [:summary {:class [:cursor-pointer]}
        [:span {:class [:text-lg :font-semibold]} "Static bulk"]
        [:span {:class muted} " · 300 rows, rendered once per session: part of every full resync, never of a patch."]]
       [:table {:class [:mt-3 :w-full :font-mono :text-xs]}
        [:tbody
         (for [i (range 300)]
           [:tr [:td i] [:td (format "%08x" (hash i))] [:td (format "%08x" (hash (str i)))]])]]])))


;; Root and shell ---------------------------------------------------------------


(defn root
  "The session's root flow."
  [ctx]
  (let [!topic (atom (first topic-names))]
    (island "app"
      {:tag   :main
       :attrs {:class [:mx-auto :max-w-4xl :space-y-6 :p-6 :pb-24]}
       :slots {:header    (header ctx)
               :feed      (feed-card ctx !topic)
               :vault     (vault-card ctx)
               :resources (resources-card)
               :bulk      (bulk)}}
      (fn [_]
        [:div {:class [:space-y-6]}
         (slot :header)
         [:div {:class [:grid :gap-6 :md:grid-cols-2]}
          (slot :feed)
          (slot :vault)]
         (slot :resources)
         (slot :bulk)]))))


(def ^:private wire-signals
  "{_wire: {frames: 0, total: 0, last: 0, page: 0, patched: '', renders: ''}}")


(defn- wire-footer
  []
  [:footer {:class [:fixed :inset-x-0 :bottom-0 :border-t :border-zinc-200 "bg-white/90" :px-6 :py-2 :font-mono
                    :text-xs :backdrop-blur :dark:border-zinc-800 "dark:bg-zinc-950/90"]}
   [:div {:class [:mx-auto :max-w-4xl :space-y-1]}
    [:div
     "frames " [:span {:data-text "$_wire.frames"}]
     " · last frame " [:span {:data-text "$_wire.last"}] " chars"
     " · full page " [:span {:data-text "$_wire.page"}] " chars"
     " · total sent " [:span {:data-text "$_wire.total"}] " chars"
     " · patched: " [:span {:data-text "$_wire.patched"}]]
    [:div {:class muted} "renders: " [:span {:data-text "$_wire.renders"}]]]])


(defn shell
  "The page, server-side rendered from the session's first `frame`. Its islands
  are patched over the stream from then on; the shell itself never is."
  [tab frame]
  (h/html
    [h/doctype-html5
     [:html {:lang "en"}
      [:head
       [:meta {:charset "utf-8"}]
       [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
       [:title "Reactive islands"]
       [:script {:src "https://unpkg.com/@tailwindcss/browser@4"}]
       [:script {:type "module" :src d*/CDN-url}]]
      [:body {:class        [:bg-zinc-50 :text-zinc-900 :dark:bg-zinc-950 :dark:text-zinc-100]
              :data-signals wire-signals
              :data-init    (str "@get('/stream?tab=" tab "')")}
       (or frame [:main {:id "app" :class [:p-6]} "Loading…"])
       (wire-footer)]]]))

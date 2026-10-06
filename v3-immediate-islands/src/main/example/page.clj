(ns example.page
  "The demo UI. The root island is rendered per session, so render counts are
  per tab. Each card demonstrates one claim from the README:

  - `feed-card`:      shared proxies (`<-`), a one-off query (`?`), island-local
                      state, and the linger.
  - `vault-card`:     scope-based ACL. Revoking stops rendering the panel, which
                      releases its resource and revokes its actions.
  - `messages`:       keyed islands. A new message renders one row; the others are reused.
  - `resources-card`: the registry, live: what is open, for whom, and what lingers.
  - `bulk`:           a big static island. It is sent on full resyncs and never patched."
  (:require
    [dev.onionpancakes.chassis.core :as h]
    [example.action :as action :refer [use-action]]
    [example.hooks :as hooks :refer [? <-]]
    [example.island :refer [defisland use-state use-watch]]
    [example.resource :as resource]
    [example.session :as session]
    [example.state :as state]
    [starfederation.datastar.clojure.api :as d*]))


(def topic-names (vec (sort (keys state/topics))))


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
  (if (= hooks/pending v)
    [:div {:class (conj muted :h-16 :animate-pulse)} "Connecting…"]
    [:div {:class [:h-16]}
     [:div {:class [:font-mono :text-4xl :tabular-nums]} (:reading v)]
     [:p {:class muted} "reading #" (:n v) " at " (:at v)]]))


;; Header -----------------------------------------------------------------------


(defisland header
  [tab uid]
  [:header {:class [:space-y-1]}
   [:h1 {:class [:text-2xl :font-bold]} "Immediate islands"]
   [:p {:class muted}
    "Tab " [:code (subs tab 0 8)] " · user " [:code (subs uid 0 8)]
    " · Open this page in several tabs, switch topics, revoke access, and hide a tab for "
    (quot session/grace-ms 1000) " s."]])


;; Shared feed ------------------------------------------------------------------


(defisland ticker
  "Renders each reading of its topic's proxy. Given another topic, it holds the
  new proxy and releases the old one, which lingers."
  [topic]
  (reading-view (<- state/$$feeds [topic])))


(defisland topic-summary
  "A one-off read: it runs when the topic changes, not when the ticker ticks.
  Switching topics while it runs cancels it."
  [topic]
  (let [info (? state/describe-topic topic)]
    [:p {:class muted}
     (if (= hooks/pending info)
       "Looking up the topic…"
       (str (:about info) ": a reading every " (:period-ms info) " ms. Looked up at " (:looked-up-at info) "."))]))


(defisland feed-card
  []
  (let [[topic set-topic!] (use-state :topic (first topic-names))]
    [:section {:class card}
     (heading "Shared feed"
       "Tabs on the same topic share one proxy. Switching moves the ticker to "
       "another; the old one lingers " (quot resource/linger-ms 1000) " s, then closes.")
     [:div {:class [:flex :gap-2]}
      (for [t topic-names]
        [:button {:class         (if (= t topic) button-active button)
                  :data-on:click (use-action [:select t] (fn [_] (set-topic! t)))}
         t])]
     (ticker topic)
     (topic-summary topic)]))


;; Restricted vault -------------------------------------------------------------


(defisland vault-feed
  []
  (reading-view (<- state/$$vault [])))


(defisland message
  {:key :n}
  [{:keys [uid text]}]
  [:li [:code {:class muted} (subs uid 0 8)] " " text])


(defisland messages
  []
  (let [posted (use-watch state/!messages)]
    [:ul {:class [:space-y-1 :text-sm]}
     (if (empty? posted)
       [:li {:class muted} "No messages yet."]
       (map message (rseq posted)))]))


(defisland composer
  "Holds the input, and reads nothing that ticks, so typing is never morphed away."
  [uid]
  (let [post (use-action :post (fn [signals]
                                 (when (state/post-message! uid (get signals "note"))
                                   {:note ""})))]
    [:div {:class [:flex :gap-2]}
     [:input {:data-bind       "note"
              :data-on:keydown (str "evt.key === 'Enter' && " post)
              :placeholder     "Message the other permitted viewers"
              :class           [:flex-1 :rounded-md :border :border-zinc-300 :bg-transparent :px-2 :py-1
                                :text-sm :dark:border-zinc-700]}]
     [:button {:class button :data-on:click post} "Post"]]))


(defisland vault-panel
  [uid]
  [:div {:class [:space-y-4]}
   (vault-feed)
   (messages)
   (composer uid)])


(defisland no-access
  []
  [:p {:class (conj muted :h-16)} "No access. Nothing under the gate is rendered: no proxy, no actions."])


(defisland vault-card
  [uid]
  (let [allowed (use-watch state/!acl #(state/allowed? % uid :vault))
        toggle  (use-action :toggle (fn [_] (state/toggle! uid :vault) nil))]
    [:section {:class card}
     [:div {:class [:flex :items-start :justify-between :gap-4]}
      (heading "Restricted"
        "Gated on your ACL, which is server state; every tab of yours follows it. "
        "Revoking stops rendering the panel: its proxy is released and its actions stop existing.")
      [:button {:class button :data-on:click toggle}
       (if allowed "Revoke" "Grant")]]
     (if allowed
       (vault-panel uid)
       (no-access))]))


;; Server resources -------------------------------------------------------------


(def ^:private cell [:py-1 :pr-4])


(defisland resources-card
  []
  (let [entries  (use-watch resource/!entries resource/summary)
        totals   (use-watch resource/!totals)
        sessions (use-watch session/!sessions session/summary)
        actions  (use-watch action/!actions count)
        queries  (use-watch state/!queries)]
    [:section {:class card}
     (heading "Server resources"
       "The proxy registry, live, across all sessions. Opened " (:opened totals)
       ", closed " (:closed totals) ". Sessions: " (:attached sessions) " attached, "
       (:detached sessions) " detached (in grace). Live action tokens: " actions
       ". One-off queries: " (:started queries) " started, " (:completed queries) " completed, "
       (:cancelled queries) " cancelled.")
     [:table {:class [:w-full :text-left :text-sm]}
      [:thead
       [:tr {:class muted}
        [:th {:class cell} "Proxy"] [:th {:class cell} "State"] [:th {:class cell} "Subscribers"]]]
      [:tbody {:class [:font-mono]}
       (if (empty? entries)
         [:tr [:td {:class (conj cell :text-zinc-500) :colspan 3} "None open."]]
         (for [[[pstate path] {:keys [state subscribers]}] entries]
           [:tr
            [:td {:class cell} pstate " " (pr-str path)]
            [:td {:class (conj cell (case state
                                      :live :text-emerald-600
                                      :connecting :text-amber-600
                                      :lingering :text-zinc-400))}
             (name state)]
            [:td {:class cell} subscribers]]))]]]))


;; Static bulk ------------------------------------------------------------------


(defisland bulk
  []
  [:section {:class card}
   [:details
    [:summary {:class [:cursor-pointer]}
     [:span {:class [:text-lg :font-semibold]} "Static bulk"]
     [:span {:class muted} " · 300 rows, rendered once per session: part of every full resync, never of a patch."]]
    [:table {:class [:mt-3 :w-full :font-mono :text-xs]}
     [:tbody
      (for [i (range 300)]
        [:tr [:td i] [:td (format "%08x" (hash i))] [:td (format "%08x" (hash (str i)))]])]]]])


;; Root and shell ---------------------------------------------------------------


(defisland app
  [tab uid]
  [:main {:class [:mx-auto :max-w-4xl :space-y-6 :p-6 :pb-24]}
   (header tab uid)
   [:div {:class [:grid :gap-6 :md:grid-cols-2]}
    (feed-card)
    (vault-card uid)]
   (resources-card)
   (bulk)])


(defn root
  "The session's root island."
  [{:keys [tab uid]}]
  (app tab uid))


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
       [:title "Immediate islands"]
       [:script {:src "https://unpkg.com/@tailwindcss/browser@4"}]
       [:script {:type "module" :src d*/CDN-url}]]
      [:body {:class        [:bg-zinc-50 :text-zinc-900 :dark:bg-zinc-950 :dark:text-zinc-100]
              :data-signals wire-signals
              :data-init    (str "@get('/stream?tab=" tab "')")}
       (or frame [:main {:id "app" :class [:p-6]} "Loading…"])
       (wire-footer)]]]))

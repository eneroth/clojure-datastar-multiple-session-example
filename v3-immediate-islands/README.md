# v3-immediate-islands: islands as functions, no reactive library

Datastar on the client. On the server, a small runtime that keeps the two
guarantees we rely on Electric for:

- **Resources live exactly as long as the view that uses them.** A proxy or a
  one-off query belongs to the island that asks for it, and is released when
  that island stops asking. Proxies are shared between sessions, where the
  platform's Electric code opens one per session, and linger briefly after
  their last reader leaves.
- **Access control is scope-based.** A subtree the user may not see is never
  rendered. Its markup is never produced, its proxies are never opened, and its
  actions never exist.

Only what changed is rendered and sent. An island is a function, defined with
`defisland`, that returns hiccup. It renders again only when its arguments, or
something it read through a hook, changed. Each island is patched into the page
on its own. Database reads go through `<-` (a shared proxy) and `?` (a one-off
task), named after the platform's Electric helpers.

This is the version we propose to build on. [v0/v1](../v0-async-server) and
[v2](../v2-reactive-islands) were earlier steps. This README covers the design
in full, why it is built this way, and how it compares with them.

## Running

- repl: `clojure -M:repl -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware]"`,
  then `(user/reload!)`. The repl also serves
  [Remontoire](https://github.com/multiplyco/remontoire), an MCP endpoint into
  the JVM, at <http://127.0.0.1:7888/mcp>. Claude Code started in this
  directory picks it up from `.mcp.json` as `v3-islands-repl`, after a one-time
  approval.
- main: `clojure -M -m example.main`
- tests: `clojure -M:test`

Open <http://localhost:8080> in two or three tabs.

![Immediate islands](resources/immediate-islands.png)

## Things to try

The footer shows each frame's size next to the full page's size, which islands
were patched, and how often each mounted island has rendered. The **Server
resources** card shows the proxy registry live, across every session.

1. **Share.** With two tabs on `alpha`, the proxy `$$feeds ["alpha"]` has two
   subscribers but was opened once. A typical frame is about 190 chars against a
   21 KB page.
2. **Switch.** Pick `beta`. `feed-card` renders with its new local state and
   gives `ticker` and `topic-summary` a new topic. `ticker` asks for the new
   proxy instead of the old one, so the old one lingers for 5 s and then closes.
   Switch back within 5 s and it is reused.
3. **Query once.** `topic-summary` reads with `?`, a 700 ms simulated query. The
   resources card counts the runs: ticker readings don't rerun it, only a new
   topic does. Click through the topics quickly and the runs you overtake are
   cancelled.
4. **Revoke.** `vault-card` stops rendering `vault-panel`, so the panel and its
   children unmount. `$$vault []` lingers and closes, and the live action token
   count drops. A POST to one of the panel's old `/act/<token>` URLs gets a 404,
   and so does one made after granting again, since a remount mints new tokens.
   While a token is live, a POST without your cookie gets a 403.
5. **Post.** Type a message and press Enter. It appears in every tab that may see
   the vault, and the action's JSON response clears the input. Each message is a
   keyed island (`message.<n>`). A new message renders `messages` and the new
   row once; the rows already shown are reused.
6. **Hide a tab.** Datastar closes a hidden tab's stream, so the session shows as
   detached. Come back within 15 s and the page is resent from cache
   (`patched: app`); nothing reopens. Stay away longer and the session closes:
   its islands unmount and release what they held.

## Writing islands

```clojure
(defisland vault-card
  [uid]
  (let [allowed (use-watch state/!acl #(state/allowed? % uid :vault))
        toggle  (use-action :toggle (fn [_] (state/toggle! uid :vault) nil))]
    [:section {:class card}
     [:button {:data-on:click toggle} (if allowed "Revoke" "Grant")]
     (if allowed
       (vault-panel uid)
       (no-access))]))
```

- **An island is a function of its arguments.** Calling one inside another
  island's hiccup places it there; the runtime decides whether it renders. It
  renders when it is new, when its arguments changed (`=`), or when something it
  read changed. Otherwise its last output is reused.
- **Ids are scoped by parent, as React keys are.** An island's slot is its
  name, unique among its siblings. For several instances under one parent, give
  a `:key` fn of the arguments: `(defisland message {:key :n} [msg] ...)` gives
  the slot `message.17`. The element id, which Datastar patches, is the path of
  slots from the root, `app/vault-card/vault-panel/messages/message.17`. So an
  island can be placed anywhere, any number of times. The id is set on the root
  element the island returns. Any key works: characters other than letters,
  digits and `-` are escaped.
- **Hooks take a key, not a call order.** Each hook names what it is, so
  conditionals and loops around hooks are fine:
  - `(use-watch ref)` or `(use-watch ref select)` reads an atom (anything
    watchable). The island renders again when the selected value changes, so
    `#(state/allowed? % uid :vault)` ignores every other user's ACL change.
  - `(use-state :k init)` returns `[value set-value!]`: island-local state, kept
    while the island is mounted. `set-value!` can be called from an action.
  - `(use-action :k handler)` returns a Datastar `@post(...)` expression, bound
    to a token that exists while the island renders it.
  - `(use-hold :k acquire)` is the primitive under `use-action`, `<-` and `?`:
    something acquired once and released when the island stops asking for it.
    A render asks for each key once. Asking twice throws, so two buttons can't
    end up sharing one action token.
- **Database reads use the platform's two helpers** (`example.hooks`):
  - `(<- pstate path)` streams `path` through a proxy shared by every session.
    Another `pstate` or `path` releases the old proxy, which lingers, and holds
    the new one. `{:init x}` stands in for the value until the first one arrives.
  - `(? f & args)` runs a function returning a Quiescent task, once per distinct
    `f` and `args`. New arguments cancel a run in flight and start another. Pass
    a named `f`: it is part of the key.
  - Both return `hooks/pending` until their first value. The Electric versions
    throw `Pending` instead.
  - Both throw in the render when what they read fails, so `try` handles a
    failure as in Electric. Uncaught, the island renders an error box. The
    island keeps the failure until it unmounts or its arguments change. A failed
    proxy is closed, so the next island to read its path opens a new one.
- **The gate is `if`.** A branch that isn't rendered holds nothing and exposes
  no actions. That is Electric's scope-based access control in ordinary code.

The same rules as React apply, and the same mistakes are possible:

- A value read without a hook (a bare `@atom`) is not tracked, so the island
  will not re-render when it changes.
- Passing a fresh closure or collection as an argument makes it unequal on every
  render, so the child re-renders every time. Pass data.
- The work is slicing islands at the right size: an island is the unit that
  re-renders and is resent. In the demo, `composer` holds the text input and reads
  nothing that ticks, so typing is never morphed away.

## How it works

### Islands and frames (`example.island`, `example.runtime`)

Each session has one runtime, used only by the session's pump.

- **Compile once, emit by appending.** A render is serialized once into a
  template: strings, plus holes where child islands go. Emitting the page
  appends cached strings and renders nothing, so an unchanged island costs
  nothing per frame.
- **Reconciling.** A frame starts from the islands whose reads changed. It
  renders them and visits their ancestors on the way down. Every other subtree
  is reused without being visited. Output equal to the previous render keeps the
  previous template, so nothing is sent.
- **Patching the topmost change.** The session compares the frame the client
  shows with the new one, top-down, by template identity. It sends each island
  whose own template changed, children included, and never also its
  descendants. Each patch is a Datastar `patch-elements` morph, targeted by id.
  SSE is one ordered stream, so the server knows exactly what the client shows.
  The whole root is resent only when that is unknown, i.e. after a reconnect.
- **Unmounting.** When an island renders without a child it placed before, that
  child and everything under it unmount. Their holds are released, their watches
  removed and their action tokens revoked. Nothing is released by hand.
- **Watching.** A session watches each ref once, however many islands read it.
  A watch callback only posts the ref to the session's mailbox. On the pump, each
  island that read the ref re-runs its selector, and renders only if the selected
  value changed.
- **Holding.** A hold is acquired on first use and released after the first
  render that doesn't use it, or on unmount. A failed render releases what it
  didn't reach before throwing; the registry's linger absorbs that.
- **Ids.** A parent records its children's ids when it renders. A child placed
  by another parent has another id, so it is another instance. Moving an island
  between parents remounts it, as in React.
- **Failures.** A render that throws renders an error box in its place, without
  children, and the exception is logged. It never fails the session.

### Resources (`example.resource`)

The registry is where "neither torn down while in use, nor kept when unused" is
enforced:

- One physical resource per key, `[pstate-name path]`, opened by the first
  subscriber, however many sessions read it.
- Islands subscribe through `<-`, which holds the subscription for as long as
  the island's renders ask for it. Nothing calls `release!` by hand.
- After the last subscriber leaves, the resource lingers for `linger-ms` (5 s)
  before closing. This absorbs churn from switching back and forth, re-granting,
  navigation and reconnects.
- A resource is a Quiescent task created under `q/compel`, because it belongs to
  the registry, not to whichever session subscribed first. Cancelling the task
  closes the resource.
- A resource whose task fails is closed. Its value becomes a failure, which `<-`
  throws in its readers, and the next subscriber opens a new one. Each
  subscription holds the value atom of the resource it subscribed to, so
  releasing a failed resource can't touch its replacement.
- `simulated-pstate` stands in for a Rama PState: a ticker loop per path, after
  a simulated connection delay.

### Sessions (`example.session`)

- **One session per tab, not per connection.** `GET /` creates the session and
  renders the page server-side from its first frame. The tab's
  `@get('/stream?tab=…')` then attaches. The session records that the client
  shows that frame, so the first attach sends only what has changed since. The
  page is served `no-store`, so a duplicated or restored tab fetches a page, and
  a tab id, of its own.
- **One page per session.** The stream also carries an id that the browser makes
  per page load, and the session belongs to the first load to attach. A copy of
  the page that still shares the tab id is told to reload, and gets a tab of its
  own. Otherwise its stream would replace the original's, and the original would
  stop receiving frames while its actions kept working.
- **Reconnects are routine.** Datastar closes a hidden tab's stream
  (`openWhenHidden` is false for GET) and reopens it when the tab is shown. The
  stream is opened with `retry: 'always'`: Datastar's default retries a stream
  that failed, but not one the server ended. A detached session keeps its
  islands, and so their resources, for `grace-ms` (15 s). Reattaching resyncs
  the root from cache without reopening anything. After the grace period every
  island unmounts and everything they held is released.
- **Unknown tabs.** A tab id the server doesn't know, because its session expired
  or the server restarted, gets a fresh session, and the client resyncs from its
  first frame. Concurrent streams for one unknown tab share one session.
- **Rendering while detached.** A session renders whenever something its islands
  read changes, with or without a client. So what the islands hold, and the
  actions they expose, always follow the current state. A frame is only sent to
  an attached client.
- **One pump per session.** A Quiescent task on a virtual thread owns all of the
  session's mutable state, its runtime included, and consumes a mailbox. A
  watched ref changing only posts to the mailbox. Rendering happens on the pump,
  never on the thread that changed the ref, so a shared resource's loop never
  renders on anyone's behalf.
- **Backpressure.** Changes that arrive while a frame is being sent coalesce
  into the next one, `frame-ms` apart, and the latest state wins. A slow client
  gets fewer, later frames, never a backlog.
- **Heartbeats.** A dead connection is only noticed on write, so a quiet session
  writes a heartbeat every `heartbeat-ms` (10 s).
- **Closing.** Events that reach a session after it closed are turned away. A
  connection is closed, so its client retries into a new session, and a page
  render gets nothing.

### Authorization (`example.action`)

- **Reads are scope-based, and the gate is `if`.** Markup for a subtree the user
  may not see is never produced. That is stronger than Electric, which ships the
  gated code in the bundle and merely doesn't mount it.
- **Writes are capabilities.** `use-action` mints an unguessable token, bound to
  the user, the first time an island renders it, and renders
  `@post('/act/<token>')`. The token is revoked once the island stops rendering
  it. So a POST can only reach closures currently rendered for that user: another
  user's token gets a 403, a revoked one a 404. The authority check happened
  when the island decided to render the action, and the closure carries it.
- **Arguments are untrusted.** Signals arrive from the client and must be
  validated, as `e/client` → `e/server` values always had to be. See
  `state/post-message!`.
- **Commands and queries.** A POST runs the command and answers 204, or JSON to
  patch signals. View updates arrive over the stream.
- **The race.** A request can race a revocation. A token lives until the
  session's next render, normally within a frame, so a request can run a moment
  after its island stopped rendering the action. Writes that matter check again
  in the write path (in the platform, in the Rama transaction). Electric has the
  same window.

## Why it is built this way

- **Reads only through `<-` and `?`.** Rendering is cheap; a Rama query is not.
  A `foreign-select` in a render runs on every re-render. Its cost compounds as
  load on Rama, where it is hard to trace back to the view that caused it, and
  caching it by hand means inventing invalidation at every call site. `<-` and
  `?` make the rule structural: a proxy is shared and streams changes, and a
  query reruns only when its arguments change. In the platform's Electric code
  they are what the team reaches for, being easier than the raw calls, so v3
  adds no guard against direct Rama calls in a render.
- **The linger.** Without it, every remount (a topic switched back, access
  granted again, a reconnect) would close and reopen a proxy, and that churn
  would land on Rama too.
- **One proxy per path, shared by every session.** The registry keys a proxy by
  `[pstate-name path]`, so every island in every session that reads a path
  shares one subscription to Rama. In the platform's Electric code each session
  opens its own through `mh/subscribe`, so N tabs on one path hold N proxies.
  Here the load on Rama scales with the distinct paths being viewed, not with
  the number of viewers. What stays per session is the fan-out: each new value
  notifies every session that reads it. A proxy doesn't know who reads it, so
  access control stays at the island (the gate is `if`), and data that differs
  per user needs the user in the path.
- **Immediate mode, not a reactive graph.** v2 built the islands as a Missionary
  DAG. It works and is as fast, but page code becomes explicit flow wiring:
  `:inputs`, `:slots` and `island/gate`. v3 shows that ordinary functions and a
  small runtime give the same guarantees, with page code that reads as plain
  Clojure and plain stack traces. The libraries we looked at:
  - **Missionary** is the best retained-graph library in Clojure, and v2 uses it.
  - **Signaali** tracks dependencies on a process-global mutable stack, so one
    graph runs at a time. Our sessions render concurrently.
  - **Javelin** has no automatic teardown, which is the lifecycle we need.
  - **Spindel** keeps running a spin that its parent no longer creates, and
    leaves teardown to garbage collection. We need teardown to be deterministic.
  - **partial-cps** gives async code without a scheduler, through a CPS
    transform. On the JVM, virtual threads and Quiescent already let code block
    cheaply.
- **Hooks keyed, not ordered.** Each hook names what it holds, so conditionals
  and loops around hooks are safe, unlike React's call order.
- **Ids scoped by parent.** A page-wide uniqueness rule can't be checked
  locally, since two parents rendered in different frames never meet. Scoping
  makes ids unique by construction, and keys only need to be unique among
  siblings.
- **No scheduler of our own.** Everything concurrent is a Quiescent task on a
  virtual thread: session pumps, proxies, `?` runs and the linger timer. The
  only timed waits are blocking calls inside those tasks. v0/v1's fixed-rate
  render loop has no counterpart, since v3 renders on change.
- **Plain hiccup, serialized by Chassis.** Building hiccup is nearly free: 0.04 µs
  for the ticker's view. Serializing a small island takes about 1.5 µs, a sixth
  of a frame. Chassis's `cc/compile` pre-serializes static parts at
  macroexpansion and halves that, with the same syntax. It applies below an
  island's root, which must stay a vector so that the runtime can put the id on
  it. We considered a server-side `$` with the shape of the platform's Electric
  macro, to ease porting. We decided against it, because it would be a second
  syntax over the same Chassis output. The Electric UI is translated once
  instead (see "Porting to the platform").

## Compared with v0/v1 and v2

[v0](../v0-async-server) re-renders every session on a timer and sends the whole
view. [v1](../v1-quiescent-vthreads) is still a copy of v0.
[v2](../v2-reactive-islands) introduced islands, the shared registry, tab
sessions and capability actions, built on Missionary. v3 keeps those designs and
replaces the island layer.

|                  | v0 / v1                                    | v2                                            | v3                                   |
|------------------|--------------------------------------------|-----------------------------------------------|--------------------------------------|
| When to render   | every session, every tick (1 s)            | when an island's inputs change                | when an island's arguments or reads change |
| What to send     | the whole view                             | the topmost islands that changed              | the topmost islands that changed     |
| Resource owner   | the SSE connection, one per session        | a shared registry: refcounted, with a linger | the same, and failed resources close |
| Session lifetime | the SSE connection                         | the tab, with a grace period                  | the tab, with a grace period         |
| Authorization    | not covered                                | gated subtrees, capability actions            | `if`, capability actions             |
| Rendering thread | one scheduler thread for all sessions      | a pump per session                            | a pump per session                   |
| Island engine    | none                                       | 192 lines + Missionary                        | 548 lines, no dependency             |

Costs, measured on this demo page. JDK 27, warmed up; v2 and v3 for one ticker
reading:

|                    | Full re-render (v0/v1 style) | v2                  | v3                  |
|--------------------|------------------------------|---------------------|---------------------|
| Time per session   | 0.2–0.4 ms every tick        | ~8.9 µs per change  | ~9.2 µs per change  |
| Allocated          | 0.25–0.9 MB every tick       | ~8.1 KB per change  | ~15.9 KB per change |
| Sent               | ~21 KB every tick; ~400 B with stream compression | ~180 chars per change | ~190 chars per change |

The full re-render's range runs from a lower bound, serializing just the page's
static table, to rendering every island from scratch through v3's runtime.

- **v0/v1's cost scales with sessions × ticks**, whether or not anything
  changed, and one thread pays it. At 0.4 ms a session, 1,000 sessions take
  0.4 s of every 1 s tick. v3's cost scales with changes, times the size of the
  islands that changed, spread across the sessions' pumps.
- **Compression closes the bandwidth gap, not the rest.** Gzip over the stream
  shrinks a repeated full page to about 400 B. The server still renders and
  serializes the whole page every tick, though, and the browser still morphs
  all of it, inputs included.
- **Resources.** v0/v1 open a subscription per session and hold it for the
  connection. N tabs reading one path hold N proxies, and a hidden tab's
  reconnect reopens them.
- **v2 and v3 take the same time.** v3 allocates about twice as much per frame,
  all of it short-lived. At 1,000 tabs receiving 2.5 frames per second, that is
  about 40 MB/s of young-generation garbage, which a generational collector
  handles easily. About 11 KB of a frame is reconciling the path to the changed
  island: building and serializing its hiccup, and the persistent maps that
  record each instance. Mutable bookkeeping could reduce it if it ever mattered.
- **v3's engine is larger than v2's own code, but has no dependency.** Its page
  code is shorter (286 lines against 318, with keyed rows and the `?` card
  added) and plain. v2's vault card needed explicit `:inputs`, `:slots` and
  `island/gate`. A v3 render is a function call, so stack traces are plain, and
  an island renders in a test harness with no flows involved.
- **What v3 gives up.** Missionary's operators, such as time-based ones, and its
  supervision semantics. Scoped lifetimes and coalescing come from v3's runtime
  and session pump instead, and are covered by the tests.

## Porting to the platform

- **Reads.** `<-` corresponds to the platform's `<-` in
  `co.multiply.app.electric.hooks`, which reads through `mh/subscribe`, an
  `m/observe` over `foreign-proxy-async`. Here, a real PState's `open` fn wraps
  `foreign-proxy-async` and publishes each value into the registry. The
  platform's `<-` also takes `:pkey`, `:id` and `:pred`; the demo's takes only
  `:init`. Since proxies are shared, an option that changes what a proxy
  delivers must be part of the registry key, or be applied per reader on top of
  the shared value. Otherwise two readers with different options would share
  one proxy. `?` means the same as the platform's `?`. A poll loop (`hooks/poll`)
  is a registry entry like the ticker.
- **Proxy restarts.** The platform's `subscribe` reopens a proxy that terminates
  ungracefully, up to `max-restart-count` times, and then emits a failure. In v3
  that loop belongs in the PState's `open` fn: subscribers keep the same entry
  across restarts, and an exhausted budget fails the task, which the registry
  turns into a failure that `<-` throws.
- **Markup.** The Electric UI is translated into hiccup once. A codemod
  (rewrite-clj) converts `($ :div {…} …)`, `($ :text …)` and literal
  `($ :props …)`. By hand: `:on!` handlers become `use-action`, `e/client`
  reactivity becomes Datastar signals, and code using `dom/node` needs
  rethinking.
- **Before production:**
  - Re-check authority in the write path for privileged writes (see "The race").
  - Give SSE writes a timeout. A pump blocked on a stalled client stops
    rendering, so its tokens and holds stop following state until the write
    fails.
  - Cap sessions per user. Any new tab id on `/stream` creates a session, which
    holds resources for the grace period.
  - Make the registry's lock per key; it is global here.
  - Keep exception messages server-side; the demo shows them in the error box.
  - Route `/stream` and `/act` to the node that holds the session. Sessions and
    tokens live in memory, so several web nodes need sticky routing.
  - Serve over HTTP/2. Every tab holds an SSE connection, and browsers allow
    about six HTTP/1.1 connections per origin.
  - Consider compression. The SDK has gzip write profiles; with targeted
    patches it matters less than with full re-renders.

## Limits and open questions

- **Untracked reads go stale**, as described under "Writing islands".
- **A shared derived value is computed per island.** Two islands that select the
  same expensive derivation each run it, and two sessions running the same `?`
  each run it too, as in Electric. To share one, put it in the proxy registry:
  a registry entry can be a derived computation.
- **Lists are keyed islands.** A list's parent re-renders when the list changes,
  and is resent with its cached rows. Datastar's `append`/`remove` patch modes
  could make growth cheaper, e.g. for streaming tokens.
- **Morphs and inputs.** An island containing an `<input>` should not depend on
  data that ticks, or morphs will fight the user's typing; `composer` is split
  out for that reason. Client-local UI state belongs in Datastar signals.
- **A failure sticks to its island.** An island whose proxy failed, after any
  restarts inside the resource, keeps the error until it unmounts or its
  arguments change, as with `?`. The demo's simulated proxies don't restart.
- **Ids grow with depth.** Each level adds a slot to the id, so deep trees send
  longer ids. They appear once per island in the HTML.
- **Consistency.** Islands read refs as they render, so a ref that changes
  mid-frame can be seen at two values; the next frame corrects it. Separate
  resources also update independently of each other.

# v0-async-server: Datastar multiple long-lived SSE connections example

The key namespaces are `session`, `sse` and `lifecycle`. One session = one SSE
connection.

- **`example.session`**: the session structure and its functions.
- **`example.sse`**: datastar-clojure SDK Ring handler that handles the SSE
  connection. Creates a session when a request comes in.
- **`example.lifecycle`**: the render loop for all clients, i.e. builds HTML
  and pushes it onto each client's SSE stream (and cleans up closed clients
  after rendering).

**`example.live`** is an example of a route that implements the lifecycle
functions for a session. Routes that create SSE connections have a
route-id -> lifecycle-fns relation (`mount`, `mounted->render`, `render` and
`unmount`, in that order; see the session namespace).

The advantage of using one SSE handler together with a unique route id is that
session handling is decoupled from feature implementation. We get a generic way
to handle SSE sessions, and implementations can live in their own namespaces.

## Running the example

- repl:

```bash
clojure -M:repl -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware]"
```

- main:

```bash
clojure -M -m example.main
```

![Multiple session example](https://github.com/kauppilainen/clojure-datastar-multiple-session-example/blob/main/resources/multiple-sessions.png?raw=true)

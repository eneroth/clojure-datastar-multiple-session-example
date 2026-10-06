# Datastar multiple long-lived SSE connections: proof of concept versions

Each directory is a self-contained version of the same proof of concept. `cd`
into one and follow its README.

| Version | Description |
|---------|-------------|
| [`v0-async-server`](v0-async-server) | Original implementation |
| [`v1-quiescent-vthreads`](v1-quiescent-vthreads) | Variant using [Quiescent](https://github.com/multiplyco/quiescent) for virtual threads (work in progress: still a copy of v0) |
| [`v2-reactive-islands`](v2-reactive-islands) | Reactive backend: a [Missionary](https://github.com/leonoel/missionary) DAG of islands, patched individually; shared, refcounted resources; sessions that outlive connections; capability-style actions |

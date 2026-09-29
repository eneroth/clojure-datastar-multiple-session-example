# Datastar multiple long-lived SSE connections: proof of concept versions

Each directory is a self-contained version of the same proof of concept. `cd`
into one and follow its README.

| Version | Description |
|---------|-------------|
| [`v0-async-server`](v0-async-server) | Original implementation |
| [`v1-quiescent-vthreads`](v1-quiescent-vthreads) | Variant using [Quiescent](https://github.com/multiplyco/quiescent) for virtual threads (work in progress: still a copy of v0) |

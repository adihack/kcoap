# Architecture decision records

Each record states one decision, why it was made and what it costs. An accepted record's decision is never
changed; a later record supersedes it. An "As built" section may be appended to record how it was
implemented. New records take the next number.

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-crypto-capabilities.md) | Crypto capabilities are asked, memoized per backend, and checked against what throws | Accepted |
| [0002](0002-delegation-not-subclassing.md) | Backends and adapters are final; extension is by delegation | Accepted |
| [0003](0003-tls-in-connectors.md) | TLS lives in connectors and listeners; DTLS is a datagram decorator | Accepted |
| [0004](0004-no-scheme-registry.md) | No scheme or transport registry: a sealed `Endpoint` and `Connector.schemes` | Accepted |
| [0005](0005-peer-link-seam.md) | One CoAP endpoint over an internal `PeerLink` seam for datagrams and connections | Accepted |
| [0006](0006-q-block.md) | Q-Block (RFC 9177) as sans-IO state machines, off by default, with kcoap's token, retry and lifetime policies | Accepted |
| [0007](0007-build-governance.md) | The build guards the architecture: common-only cores, ABI dumps, opt-in markers | Accepted |
| [0008](0008-fail-closed-message-security.md) | Fail-closed message security: resource access policies on the server, only verified responses acted on by the client | Accepted |
| [0009](0009-browser-topology-interop.md) | The browser topology is cross-tested through libcoap's own forward-proxy, with the device on the default port | Accepted |

The full design these come from, with the review findings behind each, is in
[../target-architecture.md](../target-architecture.md).

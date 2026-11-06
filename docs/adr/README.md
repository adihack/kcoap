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
| [0010](0010-server-side-tls.md) | Server-side TLS: termination at a reverse proxy now, a JVM SSLEngine listener later | Accepted |
| [0011](0011-error-model.md) | One exception type per facade, under `KcoapException`; SPI signals translated at each layer's boundary; `@Throws` for Swift | Accepted |
| [0012](0012-configuration-shapes.md) | Builders for the two configurations that grow, named-argument constructors elsewhere, no endpoint DSL, explicit `Endpoint` classes | Accepted |
| [0013](0013-durable-state-is-the-applications.md) | Durable state is an SPI the application supplies: no default sequence-number store, recipes instead of store modules | Accepted |
| [0014](0014-shared-crypto-blocks.md) | Shared crypto building blocks (X.509, SEC1, ECDSA DER, key handles) live in kcoap-crypto; no kcoap-x509 module | Accepted |
| [0015](0015-randomness-by-layer.md) | Randomness by layer: the CoAP stack's own CSPRNG, the security layers' `CryptoBackend.randomBytes`; no public `SecureRandom` | Accepted |
| [0016](0016-bom-from-published-modules.md) | One version for every module through `kcoap-bom`, generated from the published modules and checked against them | Accepted |

The full design these come from, with the review findings behind each, is in
[../target-architecture.md](../target-architecture.md). Every work package that settles a decision its §5 lists adds
a record (§4.1); 0011 to 0015 write down decisions that work packages A1 to D2 settled in code, and 0016 the BOM of
WP-C3.

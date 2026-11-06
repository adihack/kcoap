# Architecture

Status: **built for UDP, TCP and WebSockets, on every target**. The CoAP endpoint with client and
server APIs, Block-wise, Observe and Q-Block, OSCORE, EDHOC (including the post-quantum suite), the
EDHOC→OSCORE glue, the crypto backends, the UDP transport and CoAP over TCP and WebSockets (RFC 8323) exist and
are cross-tested; the protocol modules build for the JVM, Apple, Linux and the web (`js`, `wasmJs`) and pass the
same common tests on each. TLS exists as a client only: `coaps+tcp` on the JVM (WP-T1) and `coaps+ws` on the
WebSocket engine's TLS; servers terminate TLS at a reverse proxy ([ADR 0010](adr/0010-server-side-tls.md)). DTLS is
designed here and comes last. This records how kcoap stays one protocol core while running in mobile apps, browsers
and server applications, as built; [target-architecture.md](target-architecture.md) says where it is going, the
[ADRs](adr/README.md) why, and [rfc-compliance.md](rfc-compliance.md) what of each RFC is implemented.

## Module dependencies

`A ──► B` means that A's main code depends on B, as `api` unless marked `impl`. Every module also declares
kcoap-common, which every module uses ([code style](code-style.md#dependencies-and-api)), so that edge is left out
below.

```
 glue      kcoap-edhoc-oscore ──► kcoap-edhoc, kcoap-oscore, kcoap-coap, kcoap-coap-message, kcoap-crypto,
                                  kcoap-transport, impl kcoap-cbor, impl kotlinx-coroutines
 stack     kcoap-coap ──► kcoap-coap-message, kcoap-transport, kotlinx-coroutines   (CoapException)
           kcoap-oscore ──► kcoap-coap-message, kcoap-cose, kcoap-crypto, kcoap-transport,
                            impl kcoap-cbor, impl kotlinx-coroutines                  (not the stack)
           kcoap-edhoc ──► kcoap-cose, kcoap-cbor, kcoap-crypto                       (not CoAP)
           kcoap-dtls ──► kcoap-transport, kcoap-crypto              (interfaces; unpublished until its engine)
 wire      kcoap-coap-message ──► kcoap-transport                                      (no coroutines)
           kcoap-cose ──► kcoap-cbor, kcoap-crypto
 seams     kcoap-crypto ──► kcoap-common      (CryptoBackend, common fallbacks, x509)
           kcoap-cbor ──► kcoap-common
           kcoap-transport ──► kcoap-common   (no third-party dependencies)
 base      kcoap-common                       (no dependencies)

 adapters  kcoap-crypto-default ──► kcoap-crypto, impl cryptography-kotlin, impl kotlinx-coroutines   UNIVERSAL
           kcoap-crypto-bc ──► kcoap-crypto, impl Bouncy Castle                                 JVM_LIBRARY
           kcoap-transport-ktor ──► kcoap-transport, ktor-network, kotlinx-coroutines,
                                    impl ktor-network-tls                                       SOCKETS
           kcoap-transport-ktor-websocket ──► kcoap-transport, ktor-client-core, kotlinx-coroutines;
                                    impl the default engine: Darwin on Apple, CIO on JVM and Linux, Js on the web
                                                                                                UNIVERSAL
           kcoap-transport-ktor-websocket-server ──► kcoap-transport-ktor-websocket, ktor-server-core,
                                    ktor-server-websockets (no engine)                          SOCKETS
 support   kcoap-testkit ──► kcoap-transport, kotlinx-coroutines                                UNIVERSAL
           kcoap-crypto-testsuite ──► kcoap-crypto, kotlin-test, kotlinx-coroutines-test         UNIVERSAL
           kcoap-bom: no code; constrains every published module to one version
```

No core module (the base, seams, wire, stack and glue rows) depends on an adapter, and none has main code outside
`commonMain`, which `verifyCommonOnly` checks ([ADR 0007](adr/0007-build-governance.md)). Adapters declare the
smallest target tier their library supports: UNIVERSAL is every target, SOCKETS the JVM and native targets without
the web, JVM_LIBRARY the JVM ([platforms](platforms.md#modules-per-target)).

Protocol modules depend on SPIs, never on a backend or transport: applications choose
`kcoap-crypto-default` or a backend of their own, and `kcoap-transport-ktor` or their own transport.
`kcoap-crypto-bc` does not depend on `kcoap-crypto-default` either: it adds ML-KEM and ML-DSA to whatever
classical backend the application passes, `BouncyCastleCryptoBackend(DefaultCryptoBackend())` in most.
`kcoap-crypto-testsuite` is a test library: every backend's tests extend its conformance suite, which checks
the `supports` contract and NIST's ACVP vectors on each target the backend builds for, so no main code
depends on it. `kcoap-bom` is the bill of materials: its constraints are generated from the modules the build
publishes, so a consumer pins one version for all of them ([ADR 0016](adr/0016-bom-from-published-modules.md)).

`kcoap-coap-message` is CoAP's wire vocabulary: the message model, options, URIs, link format, the codecs and
the message-security SPI, with no coroutines. `kcoap-coap` builds the endpoint stack on it and exports it
(`api`), so an application that uses the stack needs nothing else. `kcoap-oscore` depends on the vocabulary
only, not on the stack, so the compiler keeps OSCORE independent of the endpoint, and OSCORE can protect the
messages of another stack, a proxy or a gateway. `kcoap-edhoc-oscore` needs both, because `EdhocResource` and
`EdhocClientHandshake.over` speak to `CoapServer` and `CoapClient`.

## Where the patterns are

| Pattern | Used for |
|---|---|
| SPI interface with a default implementation | `CryptoBackend` (`DefaultCryptoBackend`), `DatagramTransport`, `Connector` and `ConnectionListener` (the ktor adapters), `ClientMessageSecurity`/`ServerMessageSecurity` (OSCORE), `CredentialResolver` (`TrustedCredentials`), `EndpointResolver` (`EndpointResolver.Default`), `TokenGenerator`, `LogSink`, `SecurityContextRepository` (`InMemoryContextRepository`) |
| SPI with no implementation shipped yet | `CertificateValidator` (EDHOC `x5chain`, later DTLS; a common-code validator is roadmap item 13), `DatagramSecurity` (`@ExperimentalKcoapApi` until the DTLS engine) |
| SPI the application must supply | `SequenceNumberStore`: durable before `store()` returns, with recipes per runtime in `kcoap-oscore/README.md`; `EphemeralSequenceNumberStore` only for keys new in this process run. `MutableSecurityContextRepository` for an EDHOC server: `EdhocServer` adds a context per handshake, so production servers supply one with a bound; `InMemoryContextRepository` has none |
| Decorator | `CombinedRequestServer` and `CombinedRequestSecurity` around any `ServerMessageSecurity` and `ClientMessageSecurity`, OSCORE's in practice (RFC 9668); `BouncyCastleCryptoBackend` around the classical backend; a key-store backend around a software one, overriding only the private-key operations (kcoap-crypto README). Backends are final and extended only this way ([ADR 0002](adr/0002-delegation-not-subclassing.md)) |
| Strategy | EDHOC ephemeral exchange (Diffie-Hellman or KEM); message codecs per framing; `AccessPolicy` per resource (`Public`, `MessageSecurityRequired`, `Authenticated`, or a lambda) |
| Template method | `CoapResource.handleGet()` and siblings; `ObservableResource` |
| State machine over sealed states | `EdhocInitiator`, `EdhocResponder`, the UDP messaging layer |
| Callbacks (`fun interface`) and `Flow` | `EadHandler`, `EdhocSessionListener`, `EndpointEventListener`, Observe as `Flow` |
| Interceptor chain | `MessageInterceptor` for tracing and metrics |
| Builder / DSL | `CoapMessage.Builder`, `OptionSet.Builder`, the server's resource DSL, `LogConfig.Builder`; the configurations certain to grow, `CoapEndpointConfig` and `CoapClientConfig` (`CoapClientConfig { ... }`, `Builder`, `toBuilder()`, `Default`), so a new option breaks neither source nor compiled callers. Every other configuration keeps named-argument constructors |

## Layers

RFC 8613 Figure 1 already draws the stack. kcoap follows it and adds the channel-security layer:

```
App API            CoapClient / CoapServer: suspend calls, Flow for Observe, ByteArray bodies
Request/Response   tokens, Observe, inner Block-wise, Q-Block, Echo / Request-Tag, access policies,
                   EDHOC resource
OSCORE             protect / verify, identical on every transport           (end to end)
Outer Block-wise   only when a proxy has to fragment                         (not built)
Messaging          UdpMessaging:      CON/NON/ACK/RST, MID dedup, retransmission, NSTART
                   ReliableMessaging: CSM, Ping/Pong, Release/Abort, BERT
Framing            UDP header | TCP/TLS length framing | WebSocket (Len = 0)
Channel security   DTLS 1.2 over datagrams (comes last) | TLS over byte streams (clients, in the adapters)
──────────────── transport interface ────────────────
Adapters           one per runtime capability or library, thin
```

Bodies are byte arrays, assembled by Block-wise or Q-Block and bounded by `maxRequestBodySize` and
`maxResponseBodySize`; streamed bodies are planned as new functions beside them (roadmap E6).

Block-wise appears twice on purpose: RFC 8613 allows it before OSCORE (inner, end to end) and after
OSCORE (outer, for proxies), so the layers compose instead of forming a fixed stack.

**Two kinds of security, both optional, both combinable:**

| | OSCORE (+ EDHOC) | DTLS / TLS |
|---|---|---|
| Protects | the CoAP request/response | everything on one hop |
| Survives proxies | yes, including UDP↔TCP↔WebSocket↔HTTP | no, each proxy sees plaintext |
| Works in browsers | yes (through a proxy) | only TLS, terminated at the proxy |
| Peer identity | OSCORE context / EDHOC credential | PSK identity, raw public key, certificate |

The application sees the identities of both layers and authorizes on either. Message security fails
closed in both roles ([ADR 0008](adr/0008-fail-closed-message-security.md)):

- **Authorization sits between the layers.** The server asks the resource's `AccessPolicy` right after the
  resource lookup, with the method, path, peer, `securityIdentity` (end to end) and `peerIdentity` (hop),
  before anything of the Request/Response layer runs for the request: no Block1 or Q-Block1 buffering, no
  Observe registration or deregistration, no Q-Block2 burst or address check, no handler. With message
  security configured the default policy is `MessageSecurityRequired`; `Authenticated` takes either layer's
  identity; `Public` (the EDHOC resource, `/.well-known/core`) takes anyone. An Observe registration is
  one entry per endpoint and token, which only its own identity replaces or cancels; another identity's
  registration on them is answered as a plain GET.
- **The identity belongs to the security context.** `SecurityContext.identity` is what OSCORE reports for
  every request it verifies: the `OscoreIdentity` by default, the `EdhocIdentity` with the authenticated
  peer credential for a context EDHOC established, or a name a provisioned context was given. A Recipient
  ID that EDHOC hands to a later session does not carry an earlier peer's rights.
- **The client acts only on what it verified.** `ProtectedRequest.unprotectResponse` reports each response
  as `Verified` or `Unverified`. Unprotected OSCORE errors (RFC 8613 §8.2) are unverified: the
  Request/Response layer takes no decision on them (no Echo retry, no block-wise or Q-Block step, no
  capability, no end of an observation) and fails a request that has no verified answer with
  `CoapException(UNVERIFIED_RESPONSE)`. A security layer that cannot protect a request it should throws
  instead of sending plaintext, and the client fails the request with `CoapException(SECURITY)`; plaintext is
  an explicit choice (`allowUnprotected`, the EDHOC path).

## The CoAP endpoint as built

One `CoapEndpoint` runs one exchange layer over every transport it was given, through an internal link per
peer ([ADR 0005](adr/0005-peer-link-seam.md)). The layers map onto code like this (`kcoap-coap`; the
message-security SPI, like the message model and the codecs, is in `kcoap-coap-message`):

```
CoapClient / CoapServer       client.*, server.*: suspend calls, Flow for Observe, resource tree + DSL
Block-wise, Observe           BlockwiseClient / BlockwiseServer, ClientObservation / ObservationRegistry
Q-Block (RFC 9177), opt-in    QBlockClient / QBlockServer driving the sans-IO cores QBlock1Sender,
                              QBlock1Receiver, QBlock2Sender, QBlock2Receiver (stack.*)
Exchanges                     CoapEndpoint + ClientExchange: tokens, matching by (Endpoint, token)
Message security              ClientMessageSecurity / ServerMessageSecurity, called per message
──────── PeerLink (internal)  send, respond, acknowledge, reject, ping, notify; limits, capabilities;
                              how a delivery ended (Delivered, Released, Rejected, TimedOut, Failed)
DatagramLink                  ConnectionLink (one per connection, on a ConnectionDriver)
  UdpMessaging: sans-IO         ReliableMessaging: sans-IO (RFC 8323)
  one lock, receive and timer   ReliableConnections: connect, accept, reuse, release
DatagramTransport             Connector / ConnectionListener (TCP, WebSocket), InMemoryConnections
  KtorUdpTransport, InMemoryTransport, later DTLS
```

Block-wise and Observe sit above message security, so OSCORE protects each block and each notification
on its own (inner Block-wise, RFC 8613 §4.1.3.4.1); outer Block-wise for proxies is not built.

**One exchange layer, a link per peer.** The endpoint picks the link once, by the kind of the peer's
`Endpoint`, in one exhaustive `when`: `Udp`, `Secure` and `Opaque` peers go to the datagram link, `Tcp` and
`WebSocket` peers to their connection, opened through the `Connector` that serves the scheme or accepted from
a listener. There is no scheme registry ([ADR 0004](adr/0004-no-scheme-registry.md)): `Connector.schemes`
is the lookup, and a peer the endpoint has no transport for fails at once with
`CoapException(TRANSPORT_UNAVAILABLE)`, naming the scheme. Above the link, the exchange, Block-wise,
Observe, Q-Block, Echo/Request-Tag, access and message-security code sees only what the link reports:

- `limits`: the largest message, the datagram size or the peer's Max-Message-Size (RFC 8323 §5.3.1), and
  whether BERT is allowed (§6); every block size follows them.
- `capabilities`: `hasReset` (a Reset ends a forgotten observation; without one the client deregisters,
  RFC 8323 §7.4), `hasMessageTypes` (CON/NON, ACKs, NSTART, Q-Block sets and timers; without them every
  request is answered and a Q-Block body goes back to back, RFC 9177 Appendix B), `isOrdered` (Observe values
  are not needed to order notifications, RFC 8323 §7.1) and `verifiesAddress` (a connection's handshake
  already showed the client receives at its address, so no Echo address check, RFC 9175 §2.4).
- `DeliveryOutcome` for what it sent on its own: `Delivered` (with a piggybacked response), `Released` (a
  NON's NSTART hold ended), `Rejected` (a Reset), `TimedOut`, `Failed`. A connection writes a message and is
  done; a closed one fails it.

The datagram link owns what only RFC 7252 has: message types and Message IDs, ACKs and Resets,
retransmission, deduplication, NSTART and the `BodyLease` of a Non-confirmable Q-Block body, and the
notification policy of RFC 7641 §4.5 and §7 (interspersed Confirmable notifications, the Non-confirmable
slot hold). On a connection a lease is granted at once and holds nothing, and a notification has no type.
`ObservableResource.notificationType` and `CoapRequest.isConfirmable` are hints that only datagram links
follow.

**One lock per component.** The endpoint keeps the exchange registry and its closed state behind one
`Mutex`; the datagram link keeps the messaging state machine behind its own, as each connection's
`ConnectionDriver` does. None is held across a suspension: a call takes the lock, runs the state machine,
takes its effects (datagrams to send, messages to deliver, outcomes of transmissions), releases the lock
and only then performs them, under `NonCancellable`, because the state machine has committed to them and
they may belong to other exchanges. A link checks the endpoint's closed state under its own lock before it
sends anything new, and the terminal transition closes the link after it set that state, so nothing new
goes out once the endpoint closed. Request handlers, message security and transports run without any lock;
each inbound request gets its own coroutine, up to `maxConcurrentRequests`. The datagram link's receive
loop and timer loop run in the scope the endpoint was given, on its dispatcher (on Android or iOS a scope
such as `lifecycleScope + Dispatchers.Default`), and start once a server attaches or the first request or
ping goes out, so a server's resources are in place before the first request, whichever of a server and a
client sharing the endpoint is created first. Client-side
response verification (OSCORE) runs on the receive loop, before the response is queued, so a forged
notification cannot push out a genuine one; message-security backends should be fast or offload their
own work. A separate response is acknowledged or reset only after that verification and the check of
its critical options (RFC 7252 §5.4.1), and no timer answers it meanwhile: one with an unknown critical
option gets a Reset; one that fails verification is acknowledged and dropped, since a Reset would cancel
an observation (RFC 8613 §8.4.2). A connection has no Reset, so there the client deregisters an
observation whose notification it rejected (RFC 8323 §7.4).

**Exchanges with several responses.** An exchange hands on responses in one of three modes: `Single`
ends it with the first response, `Stream` keeps an observation open, and `Inbox` reports responses,
Resets, timeouts and failures to a bounded inbox that all requests of one Q-Block body share (RFC 9177
§4.3, §4.4); an event that does not fit is not taken, so the receive loop never waits for a slow driver. A
Non-confirmable response that does not fit counts as lost; a Confirmable one is left unacknowledged and
forgotten by deduplication, since the sender never sends an acknowledged message again (RFC 7252 §4.2), and
its retransmission is delivered once the driver made room. Whether a Confirmable response fits is decided
before it is verified, by reserving room in the inbox: OSCORE records the Partial IV of every response it
verifies, and the retransmission carries the same one, so it would otherwise be refused as a replay (RFC 8613
§7.4). A Non-confirmable body holds one NSTART slot through a `BodyLease`, queued like a request and
released when the body ends or at min(bytes ÷ PROBING_RATE, NON_PROBING_WAIT) (§7.2); its blocks take no
slot of their own. A Confirmable body takes a slot per block instead, so NSTART runs it in lock-step.

**The Q-Block layer** (RFC 9177; [ADR 0006](adr/0006-q-block.md)). Q-Block sits where Block-wise does,
above message security, so under OSCORE its options are Inner (RFC 9177 Table 2) and every block, 2.31 and
4.08 is protected on its own. It is off by default on both sides (`QBlockClientConfig.mode = OFF`,
`QBlockServerConfig.isEnabled = false`), and then a Confirmable request with Q-Block gets 4.02 and a
Non-confirmable one a Reset (§4.1). The rules live in four sans-IO state machines in `stack.*`: messages and
`now` go in, `QBlockAction`s and the next deadline come out, so every figure of RFC 9177 runs as a script on
virtual time. Thin drivers turn them into messages:

- `QBlockClient` chooses Q-Block or RFC 7959 per request (off on reliable transports and under message
  security unless configured, never with No-Response), finds out once per peer and protection whether the
  server takes Q-Block (`QBlockSupport`: a Confirmable `GET /.well-known/core` with Q-Block2 0/0/16, kept by
  the endpoint for all its clients), and drives uploads, downloads and observations over one inbox per body.
  A body follows its request's message type: Non-confirmable bursts of MAX_PAYLOADS blocks under one
  `BodyLease`, or Confirmable lock-step.
- `QBlockServer` runs after message security and the address check: it assembles uploads (`PartialBody`),
  serves response bodies from a bounded cache (`ServedBody`) with an ETag of its own per body, answers
  Continues and requests for missing blocks, and sends the notifications of a Q-Block registration as bodies
  (`NotificationBody`), one outstanding per observation. Before a burst to a client nothing authenticated it
  verifies the address with Echo (`AmplificationPolicy.Q_BLOCK_BURSTS`, the default, RFC 9175 §2.4).
- Under OSCORE both sides opt in (`withMessageSecurity`). `OscoreClient` takes the several responses of a
  body to a request with Inner Q-Block, each with a Partial IV new in a replay window of that request, and the
  notification bodies of such a registration by a window of the registration, with freshness by Partial IV
  per body (RFC 8613 §4.1.3.5.2, §7.4.1), so the stack leaves the order of protected notifications to it as
  before.

Its tests run the cores figure by figure (`QBlockFiguresTest`), the client and server against each other on
the in-memory network at 10 % and 30 % loss (`QBlockTest`), on real threads (`QBlockStressTest`), under
OSCORE (`OscoreQBlockEndToEndTest`), and against libcoap both ways, with a soak of 20 runs behind
`-Pkcoap.soak=true` (not yet scheduled in CI; [testing.md](testing.md#nightly-soak)).

**Concurrency rules.** Every stateful component follows them, and `ConcurrencyStressTest` checks them on
real threads, on the JVM and Kotlin/Native, through `kcoap-testkit`'s `Stress` helper:

1. Mutable protocol state belongs to one `Mutex` per component, never held across a suspension, and
   every flag that guards that state is read and written under it: the endpoint's closed state is set
   under the lock that `openExchange` and `ping` check it under.
2. Read-mostly registries are copy-on-write `AtomicReference`s: the resource tree, and the observers of
   each resource, which the registry replaces under its lock. `@Volatile` is only for values read outside
   any lock where a stale read is harmless.
3. Effects and events run after the lock is released; an event listener that throws is isolated.
4. Cleanup in `catch` or `finally` that suspends (a lock, settling a transmission, deregistering, freeing
   a Request-Tag) runs in `withContext(NonCancellable)`, since cancellation is the normal way an
   application stops work and must leak nothing. Lock-guarded sections stay non-suspending.
5. Every coroutine the endpoint launches is a failure boundary. As a last resort the endpoint's scope
   gets a logging `CoroutineExceptionHandler` when the application's scope has none, so nothing reaches
   the platform's handler, which ends the process on Android and Kotlin/Native; one the application
   installed is kept.

**Lifecycle.** An endpoint ends in one terminal transition, whichever way it ends: `close()`, the
cancellation of its scope, or a datagram transport whose `receive` fails (a socket the system closed, a
network that went away). Under the lock it records `closeCause`, `CLOSED` or `TRANSPORT_FAILED` with the
transport's error, and the messaging layer fails its transmissions; then exchanges fail, every `observe`
collector gets a terminal failure, the server stops and drops its observers, connections are released
(RFC 8323 §5.5), the transport is closed and the coroutines are cancelled. Every later request, ping or
observation fails at once with the same reason, and `awaitClosed()` waits for the coroutines as well. A
send the transport refuses with `IllegalArgumentException` (an endpoint it cannot carry) fails that
message at once with `TRANSPORT_FAILED` instead of looking like a silent peer; any other send failure is
left to retransmission, like a lost datagram. Recreating an endpoint after a failure is the application's
decision; [platforms.md](platforms.md#mobile-lifecycle) says what that means for mobile apps.
`CoapServer.close()` on a shared endpoint cancels its running handlers and takes no new observers; a request on a
connection whose handler it cancelled before the answer gets 5.03, since RFC 8323 §3.3 has every request on a
connection answered.

**Resource bounds.** Over UDP a source address proves nothing (RFC 7252 §11.4), and §11.3 warns that
answering spoofed traffic turns a server into an amplifier against the address it names. Everything a
peer can make the endpoint keep or do is therefore bounded, in total and per peer, with defaults sized for
servers. At a bound the endpoint first forgets what the RFCs let it forget, then refuses only the extra
work, with an answer at most a few bytes larger than what it received (a refusal's Max-Age), keeps serving
what it has, and reports what it refused as `EndpointEvent.LimitReached`:

| Bound | Default | Beyond it |
|---|---|---|
| `CoapEndpointConfig.maxDeduplicationEntries`, `maxDeduplicationEntriesPerPeer` | 50 000, 10 000 | the oldest entry §4.5 lets the endpoint process again (an idempotent request once answered, unless OSCORE protects it, RFC 8613 §7.4; a message answered with a Reset; a request refused or dropped before anything acted on it and before message security verified it) makes room; otherwise the new message is dropped without state or answer. Three quarters at most hold non-idempotent requests, so a POST flood never keeps out a GET, nor the responses to the endpoint's own requests |
| `CoapEndpointConfig.maxReservedMessageIds`, `maxReservedMessageIdsPerPeer` | 262 144, 65 536 | a request or separate response that needs a new Message ID is refused: §4.4 forbids reusing one early; a refused notification goes later with the latest state and keeps its observer. Responses to Non-confirmable requests are refused already at three quarters, so the endpoint keeps a quarter for what it must send. Piggybacked responses need none |
| `CoapEndpointConfig.maxConcurrentRequests` | 256 | a CON gets a piggybacked 5.03 with Max-Age, a NON is dropped, a connection gets 5.03 |
| `CoapEndpointConfig.maxExchangesPerPeer` | 256 | a request fails with `IDENTIFIERS_EXHAUSTED`; a separate response queued behind NSTART is not sent, a notification goes later |
| `CoapEndpointConfig.maxExchanges` | 16 384 | the oldest Non-confirmable message kept only for a Reset is forgotten, then the oldest Confirmable one still unacknowledged after a retransmission is given up; otherwise as `maxExchangesPerPeer` |
| `CoapServerConfig.maxObservers`, `maxObserversPerPeer`, `maxObserversPerResource` | 10 000, 32, 1 000 | the registration is served without Observe (RFC 7641 §4.1); re-registrations do not count again |
| `ReliableTransportConfig.maxPendingConnections` | 128 | an accepted connection that has not sent its CSM yet is closed at once |
| `ReliableTransportConfig.maxConnections` | 1 024 | an accepted connection beyond it, opened and accepted ones together, is closed at once |
| `CoapServerConfig.maxVerifiedAddresses` | 10 000 | the address verified longest ago is forgotten, or the oldest outstanding Echo challenge dropped; either address is challenged again when it next needs to be verified |
| `CoapServerConfig.maxConcurrentTransfers` (held-back responses) | 64 | the response held back longest for the repeat of a POST or PATCH is dropped (`HELD_BACK_RESPONSES`); that repeat runs the handler again |

An observer that neither message nor channel security authenticated gets at most eight Non-confirmable
notifications in a row, then a Confirmable one, whose ACK shows it still wants them (RFC 7641 §7).

**Address verification** (RFC 9175 §2.4 item 3, §2.6). A server sends a response larger than
`CoapServerConfig.amplificationFactor` (3) times its request to a client that neither OSCORE nor DTLS
authenticated, on a datagram transport, only once that client's address is verified. Until then it sends
a 4.01 with an 8-byte Echo value from the platform CSPRNG (never the injectable `Random`) instead,
piggybacked or Non-confirmable so that nothing is retransmitted, and reports `LimitReached` with
`AMPLIFICATION_FACTOR`. The value is bound to the address it went to; a request that returns it verifies
the address for `verifiedAddressLifetime` (1 hour from the verification, not extended by use).
`AmplificationPolicy` chooses what is checked: Q-Block2 bursts (the default; everything sent in answer to
one request counts, each later set and every retransmission of a Confirmable block, and timer-driven sets
beyond the factor stop until a Continue, a request of its own, asks), every response
(`ALL_RESPONSES`, which also challenges every Observe registration, since its notifications go on, sends
every separate response to an unverified address Non-confirmable, since retransmissions would multiply
it, and keeps the response of each POST or PATCH for the repeat of that request so it does not run
twice), or nothing (`OFF`).

**Sans-IO where it pays off.** `UdpMessaging` is the part with the subtle timing rules (back-off,
deduplication lifetimes, NSTART, superseding), so it takes datagrams and time and returns effects, and
its tests need neither a transport nor a clock; `ReliableMessaging` (RFC 8323) and the four Q-Block state machines
work the same way. They are `internal` for now: the plan's second API level, a public sans-IO engine for Netty or
Vert.x event loops, would also need the exchange, block-wise and Observe layers in that form, which they are not
yet, and waits until the `PeerLink` seam has been stable for a release
([target-architecture.md](target-architecture.md) §5).

**Time and randomness.** Every timer reads `CoapEndpointConfig.timeSource` and waits with the delays of
the endpoint scope's dispatcher, so the time source must advance with that dispatcher's clock:
`TimeSource.Monotonic` with a real dispatcher, and under `runTest` `testScheduler.timeSource`, with which
a 247-second lifetime passes in an instant. The endpoint warns once when it sees the two disagree.
Request timeouts wait on the same dispatcher clock. Retransmission jitter and the first Message ID come
from an injected `Random`; tokens from a `TokenGenerator` backed by the platform CSPRNG, with
sequence numbers from zero on each DTLS session and TLS connection (RFC 9175 §4.2), one generator per
endpoint unless the configuration names one.

**Randomness.** Two sources, by layer, and no public `SecureRandom` in kcoap-common
([ADR 0015](adr/0015-randomness-by-layer.md)):

- The CoAP stack draws its secrets, the default tokens (RFC 7252 §5.3.1) and the Echo values of address
  verification (RFC 9175 §2.4), from the internal `PlatformCsprng` in kcoap-coap: a synchronous
  `kotlin.random.Random` built on `kotlin.uuid.Uuid.random()`, which the standard library backs by the
  platform's CSPRNG on every target (`SecureRandom` on the JVM and Android, `crypto.getRandomValues` in
  browsers and Node.js, the system CSPRNG on Apple and Linux), so plain CoAP needs no crypto module.
  Retransmission jitter and the first Message ID, which need no secrecy, come from
  `CoapEndpointConfig.random`, so tests replay them.
- The security layers (OSCORE's Echo values, EDHOC's connection identifiers and ephemeral keys) draw from
  `CryptoBackend.randomBytes`, the injectable seam that known-answer tests and trace replay (RFC 9529) need.
  A custom backend may implement it with the bytes of `Uuid.random()` or with cryptography-kotlin's
  `CryptographySystem.getDefaultRandom()`, as `DefaultCryptoBackend` does; a test double that needs
  determinism overrides it.

**The DTLS readiness rules, as met:**

1. Matching and deduplication use `Endpoint` equality only: Message ID state, dedup cache, exchanges,
   observers, block-wise state and Request-Tags are all keyed by `Endpoint`.
2. `DatagramEvent.SessionEnded` (or `SessionEndedException` from a send) fails the pending Confirmable
   messages of that endpoint without retransmitting, fails its exchanges and client observations
   (`CoapException.Reason.SESSION_ENDED`), removes its server observers and block-wise state, and drops
   its deduplication, Message ID and token state.
3. Block sizes and the message size limit come from the link's limits, the transport's
   `DatagramTransport.maxDatagramSize` on a datagram link, minus a configurable reserve when message
   security is on.
4. `RequestContext` carries the `PeerIdentity` of an `Endpoint.Secure` peer next to the OSCORE
   `SecurityIdentity`.
5. One `TimeSource` per endpoint drives every timer, and it advances with the clock of the dispatcher the
   timers wait on (see "Time and randomness"). A DTLS engine schedules its retransmissions through the
   endpoint's clock and timer loop, not through a time abstraction of its own.
6. There is no multicast yet, so nothing is sent to a group through any transport.

**Observability.** A `MessageInterceptor` chain sees every message at the wire, once per message, and so only the
outer codes of OSCORE traffic. `EndpointEventListener` (several combine with `+`) gets what has no message of its
own, emitted outside the locks:

| Layer | Events |
|---|---|
| Messaging | `Retransmitted`, `TransmissionTimedOut`, `ResetReceived`, `DuplicateReceived`, `MalformedMessage`, `MessageRejected`, `SessionEnded`, `LimitReached`; on connections `ConnectionOpened`, `CsmReceived`, `ReleaseReceived`, `AbortReceived`, `AbortSent`, `ConnectionClosed` |
| Client exchanges | `ResponseRejected` (a response that failed verification, or an unverified error), `QBlockFallback`; `QBlockRecovery` and `PartialBodyDropped`, also on a server |
| Server | `RequestCompleted` (one per request: inner method and code, the resource's path in the tree, No-Response, the security identity, the duration), `SecurityRejected`, `ObserverAdded`, `ObserverRemoved` |

Every reason a metric would count by is a bounded enum, with an optional free-text `detail` for a log:
`MalformedMessage.Reason`, `MessageRejected.Reason`, `ResponseRejected.Reason`, `SecurityRejected.Reason`,
`ObserverRemoved.Reason` and `LimitReached.Limit`; `RequestCompleted` takes the resource's path from the tree, never
the client's Uri-Path, so its values stay bounded too. For a metrics library, events become counters and histograms,
and `CoapEndpoint.stats()` and `CoapServer.stats()` gauges: exchanges, running handlers, kept transmissions,
deduplication entries, reserved Message IDs and connections of an endpoint, observers and Block-wise and Q-Block
bodies of a server. Each layer writes those counts in the critical section it already has, so reading them takes no
lock and the hot path gains no lock. There is no metrics library dependency.

Logging follows [logging.md](logging.md) under the tags `coap.message`, `coap.exchange`, `coap.server`,
`coap.client` and `transport`: rejected input from a peer is logged once, at DEBUG and without a stack trace, by the
layer that decides what happens to it, so a flood of bad datagrams neither fills the log nor holds the messaging
lock for sink I/O; the default threshold is WARN.

**Errors.** Each facade has one exception type for what goes wrong at runtime, and translates the
exceptions of the layers below it at its boundary; `IllegalArgumentException` and `IllegalStateException`
are for mistakes of the caller. For `CoapClient`, `CoapEndpoint` and `CoapServer` that type is
`CoapException`, whose `Reason` says what failed. `Reason`, like `OscoreError`, may gain constants in any minor
release, so a `when` over it keeps an `else` branch ([versioning](versioning.md#how-the-build-enforces-it)):

| Signal from below | Becomes |
|---|---|
| A peer that does not acknowledge, answer or accept (RFC 7252 §4.2) | `TRANSMISSION_TIMEOUT`, `RESPONSE_TIMEOUT`, `RESET` |
| `MessageSecurityException` from `unprotectResponse`: the response is dropped (RFC 8613 §8.4) | `ResponseRejected`; if nothing verifies in time, `RESPONSE_TIMEOUT` with the last of them as cause and their count in the message |
| `SessionEndedException`, a closed connection | `SESSION_ENDED`, `CONNECTION_CLOSED` |
| Any exception from `ClientMessageSecurity.protectRequest`, such as `MessageSecurityException` | `SECURITY`, nothing sent |
| `ResponseVerification.Unverified` as the only answer | `UNVERIFIED_RESPONSE` |
| `MessageSecurityException` from `unprotectRequest` on the server | An unprotected error response (RFC 8613 §8.2), and `SecurityRejected` |
| `CoapResponseException` from a resource handler | Its response, as if returned; any other exception of a handler is a bare 5.00 |
| `CoapFormatException` | A Reset or a silent drop, or an Abort on a connection, and `EndpointEvent.MalformedMessage` |
| A lookup the `EndpointResolver` could not make at runtime | `UNRESOLVED_HOST` |
| A `coaps` URI, which needs DTLS (RFC 7252 §6.2), before DTLS exists | `TRANSPORT_UNAVAILABLE`, nothing resolved or sent |
| A `coaps+tcp` or `coaps+ws` URI (RFC 8323 §9) that no connector or listener runs TLS for | `TRANSPORT_UNAVAILABLE`, nothing resolved or sent |
| A `coaps+tcp` or `coaps+ws` URI the resolver maps to a peer not reached over TLS | `TRANSPORT_UNAVAILABLE`, nothing sent |
| The endpoint's own transport failing, or a call after `close()` | `TRANSPORT_FAILED`, `CLOSED` |

A `CancellationException` passes unchanged only when the calling coroutine was cancelled: a resolver or
security layer that cancels itself, as its own `withTimeout` does, while the caller is active fails like any
other exception of that layer. A call after `close()`, or racing it, fails with `CLOSED` at once: the closed
state is checked under the lock that registers the exchange, so no exchange is added after the terminal
transition failed the others, and a ping whose connection the terminal transition ended reports that rather
than a peer that did not answer. URIs, request options and link-format documents a peer sends are read with
`CoapUri.parseOrNull`, `CoapUri.fromOptionsOrNull` and `LinkFormat.parseOrNull`, which return null instead of
the `IllegalArgumentException` that `parse` and `fromOptions` keep for the caller's own input.

The exception of every protocol facade extends `KcoapException` (kcoap-common), so one `catch` handles the
runtime failures of any kcoap call, and the subtype says which protocol failed
([target-architecture.md](target-architecture.md) §3.6):

| Facade | Peer, network, security and lifecycle failures | Caller mistakes |
|---|---|---|
| `CoapClient`, `CoapEndpoint`, `CoapServer` | `CoapException` with its `Reason` | `IllegalArgumentException`, `IllegalStateException` |
| OSCORE | `OscoreException` with its `OscoreError` | the same |
| EDHOC | `EdhocException`; `EdhocPeerErrorException` (with `retryWith`) and `EdhocTransferException` are subtypes | the same |
| CBOR, COSE | `CborException` | the same |
| X.509 | `CertificateFormatException` | the same |
| URIs | none: `CoapUri.parse` throws `IllegalArgumentException` for text the caller supplies, peer input goes through `parseOrNull` | `IllegalArgumentException` |

The signals of the SPIs between the layers stay outside the root, since each layer translates them at its
boundary: `MessageSecurityException` (the table above, and the server's error response), `CoapFormatException`,
`SessionEndedException`, `AeadAuthenticationException` (to `OscoreException` or `EdhocException`),
`UnsupportedAlgorithmException` (to a local `EdhocException` during a handshake, a configuration mistake at
setup) and `CertificateValidationException` (to `EdhocException`).

**Swift.** Kotlin/Native turns an exception into an `NSError` only for a function that lists the exception's
type in `@Throws`, and ends the process for any other. The public suspend entry points list `KcoapException` and
`CancellationException`: the requests, `ping` and `discover` of `CoapClient`, `CoapEndpoint.ping`, the eight
EDHOC steps of `EdhocInitiator` and `EdhocResponder`, `EdhocClientHandshake.run` and `runCombined`,
`EdhocServer.completeCombined`, `PeerSecurity.install(client, uri, protection)`, `SecurityContext.derive` and
`EdhocSession.oscoreContext`. `CoapUri.parse` lists `IllegalArgumentException`, since URIs are often typed in.
The four operations of the message-security SPI, OSCORE's protection and verification among them, list
`MessageSecurityException` and `CancellationException`: Kotlin/Native requires an override to keep the
`@Throws` of what it overrides, and that SPI's signal is not a `KcoapException`. Mistakes of the caller are not
listed, so they end a Swift process as a failed precondition does. A Swift caller catches the failure as an
`NSError` of domain `KotlinException` and finds the Kotlin exception in its `userInfo` under
`"KotlinException"`; kcoap-swift-smoke's script, run by the macOS CI job, checks a request timeout and a
malformed URI this way. On the JVM the same annotation adds a `throws` clause, which only Java callers see and
which kcoap-edhoc-oscore's `ThrowsDeclarationsTest` reads back to check every list above. The failures of what the
application plugs in reach these lists through the entry point that calls it: a resolver's (a platform's DNS
exception, its own timeout) through `CoapClient` and `PeerSecurity.install` as `CoapException(UNRESOLVED_HOST)`, a
sequence-number store's or crypto backend's through `EdhocClientHandshake` and `EdhocServer` as an `EdhocException`.
`SecurityContext.derive`, called directly, passes them unchanged, so a Swift caller gives it a store that does not
throw.

**Tests.** `kcoap-testkit`'s `InMemoryNetwork` simulates loss, duplication, latency and reordering on
virtual time; its `Stress` helper runs the concurrency stress tests on real threads; the JVM tests also
run against libcoap over real UDP (`interop/README.md`). `ExchangeConformanceTest` runs one suite over a
datagram link and a connection link (kcoap-testkit's `InMemoryLink`, with `InMemorySessions` standing in
for DTLS so a datagram link can be lost), and `OscoreOverLinksTest` does the same for OSCORE.

### Over reliable transports (RFC 8323)

The same `CoapEndpoint` runs connections next to (or instead of) its datagram transport, all in common
code, sans-IO where the protocol rules are. Each connection is a `ConnectionLink`, so everything above the
link is the code that runs over datagrams:

```
CoapClient / CoapServer       unchanged; the URI scheme picks the transport
Block-wise, Observe           sizes from the link's limits (Max-Message-Size, BERT); Observe values ignored
                              on an ordered link (§7.1)
Exchanges                     CoapEndpoint: matching by (Endpoint, token) and by link
Message security              unchanged; signaling stays unprotected below it
ConnectionLink                per connection: the PeerLink of RFC 8323, no types, no Reset, Custody
ReliableConnections           per endpoint: connect on demand through Connectors, one per peer, idle
                              release, Hold-Off; accept from ConnectionListeners
ConnectionDriver              per connection: reader, ordered writer, timers, one lock, back-pressure
ReliableMessaging             per connection, sans-IO: CSM, Ping/Pong/Custody, Release, Abort, framing
Transport                     Connection SPI: ByteStreamConnection (TCP) | MessageConnection (WebSocket)
```

- **One messaging layer per connection.** Tokens, Custody bookkeeping and Release draining are per
  connection (§3.3). The endpoint keeps at most one connection per peer `Endpoint`; a released one takes
  no new requests, and a new one opens only once it is gone, which the release timeout bounds. A request
  counts itself on its connection under the connection's lock, together with the check that it still
  takes requests, so an idle Release never starts between the two. A connection opened on demand is
  counted busy for its opener until it is handed over, in one step, to every request that waited for it,
  so even an event loop that resumes them late never finds it released. Every
  exchange, inbound request and server observation holds the link it came on, so responses and
  notifications go back on that connection and a closed connection fails exactly what was on it
  (§3.4, §7.4).
- **CSM first.** Each side sends its CSM at once, without waiting for the peer's (§3.3). The peer's CSM
  must be its first message (Empty excepted, §3.4), within `csmTimeout`, or the connection is aborted.
  A client sends its first request after the peer's CSM, so block sizes follow the peer's
  Max-Message-Size from the start; §3.3 would allow sending earlier, at the cost of guessing 1152.
- **Writes in state-machine order.** The state machine's writes go into the connection's queue while
  its lock is held, and one writer drains it. Concurrent senders therefore never reorder the wire, and
  the reader is never blocked by a slow peer. Requests and responses wait while 256 KiB are queued;
  signaling never waits.
- **Notifications in freshness order.** On an in-order transport the client ignores Observe values, so
  the server holds back a notification until the registration response is out.
- **BERT** (§6) is used only when both CSMs indicate it, and never when a block size is configured.
  The final BERT block carries at most 1024 bytes, because libcoap 4.3.5 rejects longer ones.
- **Adapters are thin.** The TCP and WebSocket adapters only move bytes and frames: ktor-network
  sockets, the ktor client for WebSockets (any engine, browsers included), and the ktor server for
  WebSocket servers, in a module of its own so a browser client never pulls in ktor-server. No public
  API is platform-specific; `CoapClient`/`CoapServer` with `coap+tcp://` and `coap+ws://` URIs are the
  same on every target. `kcoap-testkit`'s `InMemoryConnections` drives the same code on virtual time.

## Three transport shapes

The RFCs define three ways to carry CoAP, and the transport interface follows them:

| Shape | RFC | Carries | Who frames |
|---|---|---|---|
| Datagram | RFC 7252 | UDP, DTLS, multicast, non-IP links (BLE, LoRa with SCHC) | one message per datagram |
| Byte stream | RFC 8323 | TCP, TLS | kcoap: Len/TKL + extended length, no Type/MID |
| Message stream | RFC 8323 | WebSocket (`/.well-known/coap`, subprotocol `coap`) | one message per frame, Len MUST be 0 |

The interfaces are in `kcoap-transport` (`Transport.kt`, `Endpoint.kt`, `PeerIdentity.kt`):

```kotlin
public interface DatagramTransport {
    public val maxDatagramSize: Int
    public suspend fun send(to: Endpoint, datagram: ByteArray)
    public suspend fun receive(): DatagramEvent       // Received(from, bytes) | SessionEnded(endpoint, reason)
    public suspend fun close()
}

public sealed interface Connection {                  // one per RFC 8323 connection
    public val remote: Endpoint
    public val peerIdentity: PeerIdentity
    public suspend fun close()
}

public interface ByteStreamConnection : Connection {  // TCP, TLS
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int
    public suspend fun write(bytes: ByteArray)
}

public interface MessageConnection : Connection {     // WebSocket
    public suspend fun send(message: ByteArray)
    public suspend fun receive(): ByteArray?
}

public interface Connector {                          // opens connections for coap+tcp, coap+ws, ...
    public val schemes: Set<CoapScheme>                // a TLS scheme only if it runs TLS for it
    public suspend fun connect(peer: Endpoint): Connection
    public suspend fun close() {}
}

public interface ConnectionListener {                 // a TCP server socket, a WebSocket endpoint
    public val scheme: CoapScheme
    public suspend fun accept(): Connection?
    public suspend fun close()
}

public sealed interface Endpoint                     // Udp, Tcp, WebSocket, Opaque, Secure
public sealed interface PeerIdentity                 // None, PreSharedKey, RawPublicKey, Certificate
```

On reliable transports one messaging-layer instance serves one connection, so tokens are scoped to
the connection and `Endpoint.Tcp` / `Endpoint.WebSocket` only describe the peer. `Endpoint.Tcp` records
TLS (`coaps+tcp` and `coap+tcp` are distinct origins, RFC 8323 §8); for a connection this side opens, a
host name stays a name, which the connector resolves and TLS checks the certificate against.

The `Endpoint` variants are final classes whose equality is the matching rule written out, not data
classes, so a field added later cannot change what matches: `Udp` is an address and a port (RFC 7252
§5.3.2), its host kept in `IpLiteral` form; `Tcp` and `WebSocket` are the peer as RFC 8323 §8 names it,
compared as RFC 7252 §6.6 compares URIs (hosts case-insensitively, IP literals in RFC 5952 form, a WebSocket
URI without a port with its scheme's default), with TLS and plain peers apart on the same host and port;
`Opaque` compares link and address exactly; `Secure` is the DTLS session and epoch. A connection is kept per
equal `Endpoint`, so `ws://h/.well-known/coap` and `ws://h:80/.well-known/coap` share one.

Everything above the messaging layer sees exchanges and links, never sockets. There is no URI scheme
registry ([ADR 0004](adr/0004-no-scheme-registry.md)): the client's `EndpointResolver` turns a URI into an
`Endpoint` (`EndpointResolver.Default` takes IP literals for every scheme, `localhost` for datagrams, and
leaves host names of RFC 8323 schemes to the connector), `CoapScheme` maps each scheme to a `TransportKind`
and a channel security, and the endpoint picks the link by the kind of `Endpoint`: its datagram transport for
`coap` (and `coaps` once DTLS exists), the `Connector` whose `schemes` include `coap+tcp`, `coaps+tcp`,
`coap+ws` or `coaps+ws`. A scheme nothing serves fails with `CoapException(TRANSPORT_UNAVAILABLE)`. A connector
that lists `coaps+tcp` or `coaps+ws`, or a listener that reports one, says by that that it runs TLS for it: the
client sends such a URI only then, and only to a peer the resolver maps to TLS, so nothing meant for TLS goes out
in plaintext. Which runtime has which TLS is in [platforms.md](platforms.md#tls-support-per-runtime); servers
terminate TLS at a reverse proxy for now ([ADR 0010](adr/0010-server-side-tls.md)).

## DTLS

DTLS is in scope. RFC 7252 §9 makes the **NoSec and RawPublicKey modes mandatory to implement**, so a
CoAP implementation without DTLS is not RFC 7252 compliant.

### Delivery: prepared now, implemented last

The DTLS engine is a separate feature that comes at the end. Everything around it is in place now, so
adding it later changes no other module:

| In place now | Where |
|---|---|
| `Endpoint.Secure`: equality by DTLS session and epoch, address excluded (CID rebinding) | `kcoap-transport` |
| `DatagramEvent.SessionEnded` and `SessionEndedException` | `kcoap-transport` |
| `PeerIdentity` (None, PreSharedKey, RawPublicKey, Certificate) and `SecurityMode` | `kcoap-transport` |
| `DatagramSecurity` (coaps): the interface and its contract, `@ExperimentalKcoapApi` until the engine implements it | `kcoap-transport` |
| `CoapScheme`: the six schemes, default ports, transport kind, channel security, ALPN `coap` | `kcoap-transport` |
| `DtlsEngine` and `DtlsEffect`: the sans-IO engine interface | `kcoap-dtls` |
| `DtlsCipherSuite`: the two mandatory suites, CCM-8 parameters, record overhead incl. CID | `kcoap-dtls` |
| `DtlsConfig` with RFC 7925 defaults and per-mode validation | `kcoap-dtls` |
| `DtlsCredentials`: `PskStore`, `DtlsLocalCredential` (a `PrivateKey` handle that can stay in KeyStore/Enclave/HSM, the same kind EDHOC's `LocalCredential` takes), `RawPublicKeyTrust` | `kcoap-dtls` |
| `CertificateValidator`, X.509 and SubjectPublicKeyInfo parsing (the `x509` package) and DER↔raw ECDSA signatures (`EcdsaSignatureCodec`), all shared with EDHOC | `kcoap-crypto` |

The rules every layer above must follow so that DTLS drops in without changes:

1. **Match and deduplicate by `Endpoint` equality**, never by host and port. That alone implements the
   RFC 7252 §9.1 session-and-epoch rule and rejects NoSec responses to DTLS requests.
2. **Handle `SessionEnded`**: fail pending Confirmable messages and exchanges for that endpoint, end its
   observations, never retransmit to it (no retransmission across epochs).
3. **Take sizes from `maxDatagramSize`**, never from a constant: maximum message size and block size
   shrink by the DTLS record overhead (`DtlsCipherSuite.maxPlaintextLength`).
4. **Pass `PeerIdentity` to authorization** next to the OSCORE identity.
5. **Use the shared clock** for every timer, so the DTLS engine runs on the same virtual time in tests.
6. **Never send multicast through a secured transport.**

What the DTLS feature itself adds later (roadmap E1): the `DtlsEngine` implementation, a `DatagramSecurity` that
drives it, the RFC 6920 identifier and RFC 7252 §9.1.3.3 name check, the TLS 1.2 PRF, a `DatagramTransport`
capability that says the transport runs DTLS (so the client stops refusing `coaps` for it), and Connection IDs
(RFC 9146). ECDH and ECDSA on P-256 and the DER↔raw ECDSA conversion (`EcdsaSignatureCodec`) are in place already,
for EDHOC and X.509; certificate mode also needs the X.509 path validator (roadmap item 13). kcoap-dtls is built
and tested but not published until then.

### What has to be supported

| Mode | Mandatory cipher suite | Identity |
|---|---|---|
| PreSharedKey | `TLS_PSK_WITH_AES_128_CCM_8` | PSK identity |
| RawPublicKey (RFC 7250) | `TLS_ECDHE_ECDSA_WITH_AES_128_CCM_8`, secp256r1, SHA-256 | RFC 6920 hash of the key, at least `sha-256-120` |
| Certificate | same suite | request authority matched against SubjectAltName URI, else CN, no wildcards |

RFC 7925 (the DTLS 1.2 IoT profile, which RFC 8323 also applies to TLS) adds: session resumption for
clients, `max_fragment_length` (RFC 6066) on clients, Server Name Indication, extended master secret
(RFC 7627, SHOULD), `cached_info` (RFC 7924) in certificate mode, renegotiation disabled, no
compression, and a 9 s initial handshake retransmission timer. RFC 9146 Connection ID is the answer to
NAT rebinding and is needed in practice for mobile clients and battery-powered devices.

### Why kcoap implements DTLS itself

None of the platform stacks can negotiate the mandatory suites:

| Stack | PSK | Raw public keys | AES-128-CCM-8 | Connection ID | Where |
|---|---|---|---|---|---|
| JSSE (SunJSSE) | no | no | no | no | JVM |
| Android platform (Conscrypt) | no DTLS in the platform `SSLContext` | | | | Android |
| Apple Network.framework | no PSK suites in the DTLS cipher enum | no | no CCM suite at all | no | iOS, macOS |
| Browsers | no DTLS API | | | | JS, Wasm |
| Bouncy Castle `bctls` | yes | yes | yes | yes (1.76+) | JVM, Android only |

Apple's `tls_ciphersuite_t` lists only CBC, GCM and ChaCha20 suites and DTLS 1.2 as the newest DTLS
version. Wrapping would mean Bouncy Castle on the JVM plus a C library (tinydtls, OpenSSL or wolfSSL)
through cinterop on Apple: two stacks with different bugs, a native dependency on iOS, and still nothing
in browsers.

A Kotlin DTLS 1.2 in common code needs only what kcoap already needs for EDHOC: AES-CCM (done, with
8-byte tags and 12-byte nonces), HMAC-SHA-256 (done; the TLS 1.2 PRF is built on it), ECDHE on P-256
and ECDSA on P-256. Scope is deliberately the CoAP profile, not general TLS: CCM-8 suites, P-256,
SHA-256, PSK, RPK and X.509, plus the RFC 7925 extensions above.

The `DtlsEngine` interface stays pluggable, so a Bouncy Castle adapter can replace the Kotlin engine on
the JVM, and serves as a differential oracle in tests.

### Where DTLS sits

DTLS is a sans-IO engine between the datagram transport and the CoAP messaging layer
(`kcoap-dtls/DtlsEngine.kt`, interface only):

```kotlin
public interface DtlsEngine {
    public suspend fun onDatagram(now: Duration, from: Endpoint, datagram: ByteArray): List<DtlsEffect>
    public suspend fun send(now: Duration, to: Endpoint, data: ByteArray): List<DtlsEffect>
    public suspend fun onTimer(now: Duration): List<DtlsEffect>
    public suspend fun close(now: Duration, session: Endpoint.Secure): List<DtlsEffect>
}
// DtlsEffect: Transmit, Deliver, ScheduleTimer, SessionEstablished, SessionEnded, SendFailed
```

A `DatagramSecurity` implementation drives the engine and returns a `DatagramTransport`. Its datagrams
come from an `Endpoint.Secure(session, epoch, via, identity)`. That makes the RFC 7252 §9.1 binding
rules structural instead of something the messaging layer has to remember:

- A response, ACK or RST matches only when **the DTLS session and the epoch are the same** as the
  request's. Endpoint equality includes both, so a plaintext response to a DTLS request never matches.
- **Retransmissions MUST NOT cross epochs**: when a session goes down or a new handshake starts, the
  messaging layer fails the pending Confirmable messages of the old endpoint instead of resending them.
- CoAP is only sent as DTLS application data, and the CoAP client should be the DTLS client.

### Server side (with the engine)

- Stateless cookie exchange (HelloVerifyRequest, RFC 6347 §4.2.1) before any handshake state is
  allocated, with a rotating cookie secret.
- Connection IDs chosen by the server can carry a node identifier, so a UDP load balancer can route by
  CID and a client survives both NAT rebinding and a move to another node.
- Session-ticket or session-cache keys shared across nodes for resumption.
- Limits on concurrent handshakes per address, bounded handshake reassembly buffers, record replay
  window (RFC 6347 §4.1.2.6), key material zeroed after use.
- Credentials come from resolvers: PSK identity → key, raw public key → allowed or not, certificate →
  `CertificateValidator`.

### TLS for coaps+tcp and coaps+ws

RFC 8323 §9.1 applies RFC 7925 to TLS too, with PreSharedKey, RawPublicKey or Certificate depending on the device,
and RFC 7525 recommendations for less constrained deployments. TLS lives in the connectors and listeners of each
runtime ([ADR 0003](adr/0003-tls-in-connectors.md)); what exists per runtime is in
[platforms.md](platforms.md#tls-support-per-runtime).

- **Built: Certificate mode, clients.** `KtorTlsConnector` (kcoap-transport-ktor, JVM and Android) runs `coaps+tcp`
  on ktor-network-tls, TLS 1.2 in ktor's own implementation, validates the server's chain against the platform trust
  store or given anchors, checks the host name itself (RFC 9525 §6) and takes a pinning hook. `coaps+ws` runs on the
  WebSocket engine's TLS where that engine authenticates the server (URLSession on Apple, the browser's `wss`), or
  on an `HttpClient` the application passes.
- **Servers: a TLS-terminating reverse proxy** in front of the plain listeners, for every runtime
  ([ADR 0010](adr/0010-server-side-tls.md)); a JVM listener on JSSE's `SSLEngine` (ALPN, TLS 1.3, client
  certificates) is the later option, and a Network.framework adapter would bring TLS to Apple servers (E10).
- **Planned: PSK and RawPublicKey over TCP** need kcoap's own engine in stream mode, inside a `coaps+tcp` connector
  and listener like any other TLS. The TLS 1.2 handshake is the same as DTLS 1.2 minus cookies, fragmentation and
  retransmission, so this follows the DTLS engine.

### Later: DTLS 1.3

RFC 9147 is optional for now. Deployed CoAP devices use DTLS 1.2, libcoap supports DTLS 1.3 only with
wolfSSL, and the TLS/DTLS 1.3 IoT profile (draft-ietf-uta-tls13-iot-profile) is still a draft. The
engine interface does not change.

## Streams

The RFCs have three things that behave like streams. They are abstracted at different layers:

| What | RFC | Layer | Kotlin shape |
|---|---|---|---|
| CoAP over TCP, TLS, WebSocket | RFC 8323 | transport | `ByteStreamConnection`, `MessageConnection` |
| Observe notifications | RFC 7641 | API | `Flow<CoapResponse>`, cold: collecting registers, cancelling deregisters |
| Large bodies | RFC 7959, RFC 9177 | API | `ByteArray`, assembled by Block-wise or Q-Block and bounded by the body-size limits; a streamed body (a `Source`/`Sink` or `Flow` API beside the `ByteArray` one) is planned, roadmap E6 |

EDHOC has no streams: it is message based and transport agnostic. Over CoAP its messages are
correlated by a prefixed connection identifier (RFC 9528 Appendix A.2), and kcoap-edhoc-oscore carries them
in the application model like any other request: `EdhocServer.handle(payload, peer)` returns a
`CoapResponse`, and `EdhocClientHandshake` sends through a `suspend (CoapRequest) -> CoapResponse` port that
`CoapClient.request` fits, so nothing of the messaging layer (type, Message ID, token) reaches EDHOC.

RFC 8323 specifics the reliable messaging layer handles: no Type or Message ID, each side sends a CSM
(7.01) first, Ping/Pong (7.02/7.03), Release (7.04), Abort (7.05), Max-Message-Size defaulting to 1152,
and BERT for larger blocks. Schemes: `coap+tcp` (5683), `coaps+tcp` (5684, ALPN `coap`), `coap+ws`,
`coaps+ws`.

## Platform adapters

Built, per runtime ([platforms.md](platforms.md) has the minimum versions and the TLS details):

| Runtime | UDP (`coap`) | TCP (`coap+tcp`) | TLS (`coaps+tcp`) | WebSocket client (`coap+ws`, `coaps+ws`) | WebSocket server | DTLS (`coaps`) |
|---|---|---|---|---|---|---|
| JVM server | `KtorUdpTransport` (ktor-network) | `KtorTcpConnector`, `KtorTcpListener` | client: `KtorTlsConnector`; server: a reverse proxy | `KtorWebSocketConnector` on CIO, or the application's `HttpClient` for `coaps+ws` | `KtorWebSocketListener` on the engine the application passes | — (E1) |
| Android | as on the JVM | as on the JVM | client: `KtorTlsConnector` | as on the JVM; OkHttp through an application's `HttpClient` | not for apps | — (E1) |
| iOS, macOS | `KtorUdpTransport` (BSD sockets) | `KtorTcpConnector`, `KtorTcpListener` | — (no TLS in ktor-network on native) | `KtorWebSocketConnector` on the Darwin engine (URLSession) | `KtorWebSocketListener` on the engine passed (CIO, without TLS) | — (E1) |
| Linux | `KtorUdpTransport` | `KtorTcpConnector`, `KtorTcpListener` | — (a proxy for servers) | `KtorWebSocketConnector` on CIO (no TLS) | `KtorWebSocketListener` on the engine passed (CIO, without TLS) | — (E1) |
| Browser (js, wasmJs) | **no** | **no** | **no** | `KtorWebSocketConnector` on the browser's WebSocket | no | **no** |
| Node.js (js, wasmJs) | no adapter (ruled out, §3.10 of the plan) | no adapter | no | `KtorWebSocketConnector` on Node's WebSocket | no | no |

All adapters are thin: they move bytes, frames and datagrams, own TLS where there is any, and hold no CoAP logic,
pooling or retry, which the endpoint does for every transport. In tests, `kcoap-testkit`'s `InMemoryNetwork` and
`InMemoryConnections` stand in for all of them on virtual time.

Planned, each as an adapter of its own: a Network.framework adapter for Apple platforms (UDP, TCP and TLS with the
system's policies: cellular, VPN On Demand, multicast; E10), a JVM listener on JSSE's `SSLEngine` (ADR 0010), JVM
server adapters for epoll and `SO_REUSEPORT` if benchmarks ask for them, and OSCORE over HTTP (RFC 8613 §11) when
there are users for it. Multicast needs native APIs: NIO `join()` on the JVM, Network.framework plus Apple's
multicast entitlement on iOS. iOS also needs the Local Network permission for LAN peers.

## Browsers

kcoap runs in browsers and in Node.js as the `js` and `wasmJs` targets, from the same common code as
everywhere else:

- CoAP messages, options, URIs and both codecs; the client and server stack over `kcoap-transport-ktor-websocket`
  (`coap+ws`, and `coaps+ws` on the browser's own TLS), or over a `DatagramTransport` the application supplies;
- OSCORE, EDHOC (every method; the suites WebCrypto can run) and the EDHOC→OSCORE glue with the combined request,
  end to end through a proxy to a device.

Browsers have no UDP or TCP, so `kcoap-transport-ktor` builds no web targets. A Node.js socket transport is ruled
out too: one js artifact serves browsers and Node.js, so socket code in it would ship to browsers
([target-architecture.md](target-architecture.md) §3.10). The WebSocket client's js tests run on Node.js against an
npm `ws` server and in a headless browser against a JVM listener ([testing](testing.md#continuous-integration)).

**Cryptography in the browser** is WebCrypto through `DefaultCryptoBackend`, plus common code. The
backend never asks which platform it is on; it asks the provider what it has, and fills gaps with
common-code fallbacks where those are small and handle only public data:

| Need | WebCrypto | How kcoap handles it, on every platform |
|---|---|---|
| SHA-256/384, HMAC, AES-GCM, ECDH and ECDSA on P-256/P-384 | yes | used directly |
| SHAKE256, KMAC256, HKDF, AES-CCM | no | common code (CCM on the AES block below) |
| AES block for CCM | no ECB | AES-ECB where the provider has it, else AES-CTR on a zero block with the input as the counter |
| ECDH from an x-only EDHOC key (`02 \|\| x`) | not reliably | EDHOC expands it with `Sec1` (kcoap-crypto) before any backend sees it; the SPI takes uncompressed points only |
| X25519, Ed25519 | in current browsers and Node.js | used directly; `UnsupportedAlgorithmException` where missing |
| ChaCha20/Poly1305 | no | `UnsupportedAlgorithmException`: EDHOC suites 4 and 5 cannot run |
| 192-bit AES keys | not in Chromium | `UnsupportedAlgorithmException` there |
| ML-KEM, ML-DSA | no | only in `kcoap-crypto-bc` (JVM) |

Applications ask in advance with `crypto.supports(...)` and `CipherSuite.isSupportedBy(crypto, method)`,
which put the question the same way on every platform; EDHOC asks them itself and offers only the suites
the backend runs (WP-A2). Two more things differ in practice, not in
code: every WebCrypto call is asynchronous, so CCM costs one promise per AES block (fine at CoAP sizes),
and a WebCrypto rejection is not a Kotlin exception, so the backend converts it before protocol code
sees it.

A browser can only open WebSockets or make HTTP requests, so it reaches devices through a proxy:

```
browser ──coaps+ws / HTTPS──► proxy ──coap / coaps──► device
          OSCORE + EDHOC end to end, untouched by the proxy
```

- libcoap's `coap-server -P` is such a proxy. The interop suite runs a WebSocket-only kcoap client through it to a
  kcoap UDP device, with OSCORE, EDHOC and the combined request end to end, and pins the proxy's deviations
  ([ADR 0009](adr/0009-browser-topology-interop.md)).
- DTLS and TLS end at the proxy. End-to-end security for browsers comes only from OSCORE + EDHOC,
  which do not protect the messaging layer or the token and therefore survive the translation.
- RFC 8613 §11 carries OSCORE over HTTP directly: POST, `OSCORE` header, `application/oscore`. kcoap has no
  HTTP transport yet; it waits for users who need it.

## Servers

What a server has today, all in the one endpoint (see "Resource bounds" and "Address verification" above):

- **Every peer-driven resource bounded**, in total and per peer, with server-sized defaults: deduplication
  entries, Message IDs, concurrent requests, exchanges, observers in total, per peer and per resource, pending
  and open connections, verified addresses, held-back responses, Q-Block bodies; an EDHOC server bounds its
  pending handshakes in total and per peer and expires them. At a bound the endpoint first forgets what the RFCs
  let it forget, then refuses only the extra work and reports `EndpointEvent.LimitReached`.
- **Amplification mitigation** (RFC 9175 §2.4): address verification with Echo before large answers to
  unauthenticated clients.
- **Rate limiting by the application**: `CoapResponse.tooManyRequests` and the client's `RetryAfter` are
  built (RFC 8516); a per-client limiter in the server is not, since RFC 8516 §3 warns that per-client
  state can add to the load.
- **State keyed by security context where the protocol allows it**: OSCORE contexts are not bound to addresses, so
  a client that changes address keeps its context; exchanges, observations and block-wise state follow the RFCs and
  are keyed by `Endpoint`.
- **Hooks for metrics**: `EndpointEventListener` with `RequestCompleted` per request and `SecurityRejected`,
  `CoapEndpoint.stats()` and `CoapServer.stats()` for gauges, `MessageInterceptor`, `EdhocServerEventListener`; no
  metrics library.

Planned: a public sans-IO API level, so the protocol cores can run in a Netty or Vert.x event loop, once the
internal `PeerLink` seam has been stable for a release ([target-architecture.md](target-architecture.md) §5);
several sockets per endpoint (`SO_REUSEPORT`) and replay windows and EDHOC sessions shared across nodes, if
benchmarks and deployments ask for them.

## Extension points

- **Clock and timers**, injectable, so tests run on virtual time.
- **Randomness and ephemeral keys**, injectable, for RFC 9529 and DTLS trace replay
  ([ADR 0015](adr/0015-randomness-by-layer.md)).
- **Transports**: `DatagramTransport`, `Connector` and `ConnectionListener` (kcoap-transport); an application's own
  transport, a BLE or LoRa link say, is a `DatagramTransport` with `Endpoint.Opaque` peers.
- **Resolution**: `EndpointResolver` turns a URI into an `Endpoint`; DNS for datagram host names is the
  application's resolver.
- **Message security**: `ClientMessageSecurity` and `ServerMessageSecurity` (kcoap-coap-message), which OSCORE
  implements and RFC 9668 decorates.
- **Authorization**: an `AccessPolicy` per resource, which sees the method, the path, both identities and the peer
  ([ADR 0008](adr/0008-fail-closed-message-security.md)).
- **Storage**: OSCORE contexts (`SecurityContextRepository`, and `MutableSecurityContextRepository` where
  EDHOC adds them) and persisted sequence numbers (RFC 8613 Appendix B.1.1), supplied by the application
  ([ADR 0013](adr/0013-durable-state-is-the-applications.md)); replay windows, EDHOC state between messages and DTLS
  session tickets are not persistable yet.
- **Credentials**: resolvers for EDHOC credentials, PSKs and raw public keys; one `CertificateValidator`
  shared by EDHOC (`x5chain`) and DTLS/TLS. Certificates and raw public keys are parsed in kcoap-crypto's
  `x509` package (`X509Certificate`, `SubjectPublicKeyInfo`), which both protocols use and which throws
  only `CertificateFormatException`, whatever a peer sends. Parsing is not validation, and kcoap ships no
  validator yet: trusting an `x5chain` today means pinning or a validator the application supplies. The
  planned default is a common-code validator for the RFC 7925 profile, verifying signatures through
  `CryptoBackend.verify` with an injected clock, so it behaves the same on every platform; validators on the
  operating system's trust store (JVM `CertPathValidator`, Apple `SecTrust`) may follow as optional adapters
  ([roadmap](roadmap.md)). What is trusted today:
  - `TrustedCredentials` without a validator trusts only its pinned credentials; any other certificate
    chain fails closed.
  - With a validator, every chain the validator accepts authenticates, whoever its end entity is. A
    validator authenticates, it does not authorize: the application decides who may connect afterwards,
    on `EdhocSession.peerCredential` (its `certificate`) and `peerCredentialIdentifier`, or in a
    `CredentialResolver` that wraps `TrustedCredentials` (kcoap-edhoc README). A stub validator that
    accepts everything trusts every certificate.
- **Key stores**: a `CryptoBackend` decorator that overrides `sign`, `keyAgreement` and `decapsulate` for
  its own `PrivateKey` subclass and delegates the rest to a software backend. EDHOC (`LocalCredential`) and
  DTLS (`DtlsLocalCredential`) take the same handles, and protocol code releases the keys it owns with
  `PrivateKey.destroy()`.
- **EAD handlers** for EDHOC.
- **Logging and metrics** as hooks, no logging framework dependency: a `LoggerFactory` per component, `LogSink`s,
  `EndpointEventListener`, `MessageInterceptor` ([logging](logging.md)).

## Decisions taken for multiplatform

1. **`CryptoBackend` is `suspend`**, because WebCrypto is promise based. Done.
2. **AES-CCM is common code on the platform AES block**, from AES-ECB where the provider has it and
   from AES-CTR otherwise (encrypting a zero block with the input as the counter yields the encrypted
   input), as on WebCrypto. Done.
3. **ECDH, ECDSA, EdDSA, KEMs and signatures are in the SPI**, with opaque private-key handles that
   protocol code releases with `destroy()`. Public keys are raw: P-256 and P-384 uncompressed, expanded
   from compressed form with `Sec1` before the SPI. COSE uses raw `r || s` ECDSA signatures; DTLS and
   X.509 use DER and convert with `EcdsaSignatureCodec`. Done.
4. **`js` and `wasmJs` targets**, with `browser()` and `nodejs()`, for every module except the
   adapters around runtime-bound libraries (`kcoap-transport-ktor`: sockets; `kcoap-crypto-bc`: JVM).
   The common tests run on Node.js for both. Done.
5. **Capabilities, not platforms.** Code never branches on the target. A backend reports what its
   platform lacks with `UnsupportedAlgorithmException`, `supports(...)` asks that question in advance,
   and gaps get common-code fallbacks where they are small and safe (the AES block, SHAKE256, point
   decompression in kcoap-crypto's `Sec1`). Tests skip by capability too, and `kcoap-crypto-testsuite`
   checks on every target that each backend's answers are true: what it says it runs works, and what it says
   it lacks throws `UnsupportedAlgorithmException` ([platforms](platforms.md#algorithms-and-edhoc-suites-per-target)
   has its report per target).
6. **Tiers by runtime capability, cores checked to stay common.** Adapters are split by the library they wrap, not
   by platform, and declare the smallest tier it supports; `verifyCommonOnly` keeps core main code in `commonMain`,
   and the ABI dumps keep the public API deliberate ([ADR 0007](adr/0007-build-governance.md)). Done.
7. **One exception root for Swift.** Every protocol facade fails with a `KcoapException`, which the public suspend
   entry points list in `@Throws`, so Swift gets an `NSError` ([ADR 0011](adr/0011-error-model.md)). Done.

## Modules

| Module | Scope | State |
|---|---|---|
| `kcoap-bom` | The bill of materials: one version for every published module, generated from the published-module list | done |
| `kcoap-common` | Logging API and sinks, hex, constant-time compare and `wipe`, `KcoapException`, the opt-in markers | done |
| `kcoap-crypto` | The `CryptoBackend` SPI with `supports`; AES-CCM, HKDF, SHAKE256/KMAC256, `Sec1`, `EcdsaSignatureCodec`; the `x509` package; `CertificateValidator` | done; no validator yet (roadmap item 13) |
| `kcoap-crypto-default` | The default backend on cryptography-kotlin: JCA, CryptoKit and CommonCrypto, OpenSSL 3, WebCrypto | done |
| `kcoap-crypto-bc` | ML-KEM and ML-DSA on Bouncy Castle, a decorator of a classical backend; JVM only | done |
| `kcoap-crypto-testsuite` | Conformance suite for crypto backends, for tests: the `supports` contract, NIST's ACVP ML-KEM and ML-DSA vectors, the capability report per target | done |
| `kcoap-cbor` | Deterministic CBOR (RFC 8949 §4.2) and CBOR sequences (RFC 8742) | done; strict decoding open (checklist foundations-1) |
| `kcoap-cose` | COSE structures, headers, COSE_Key, CWT Claims Sets (RFC 9052, 9053, 8392) | done for what OSCORE and EDHOC need |
| `kcoap-transport` | Transport interfaces, `Endpoint`, `PeerIdentity`, `CoapScheme`, `IpLiteral`, `DatagramSecurity` | done; `DatagramSecurity` experimental until the DTLS engine |
| `kcoap-coap-message` | Message model, options, URIs, link format, the UDP and RFC 8323 codecs, the message-security SPI; no coroutines | done |
| `kcoap-coap` | The endpoint with client and server over UDP and RFC 8323 connections, block-wise and BERT, Observe, Q-Block, Echo and Request-Tag, access policies, `CoapException` | UDP, TCP and WebSocket done |
| `kcoap-oscore` | RFC 8613 behind the message-security SPI, on `kcoap-coap-message` only | done; Outer Block-wise, protected signaling and Appendix B.2 open |
| `kcoap-edhoc` | RFC 9528 Initiator and Responder, DH and KEM suites, credentials, EAD | done; suite 25, CWT and C509 credentials open |
| `kcoap-edhoc-oscore` | RFC 9528 Appendix A, RFC 9668 | forward flow and combined request done; reverse flow open |
| `kcoap-transport-ktor` | UDP and TCP on ktor-network, and a TLS client for `coaps+tcp` on ktor-network-tls (JVM only); JVM, Apple and Linux (no web targets: ktor-network) | UDP, TCP and the JVM TLS client done (WP-T1); server-side TLS at a reverse proxy for now ([ADR 0010](adr/0010-server-side-tls.md)) |
| `kcoap-transport-ktor-websocket` | WebSocket client on the ktor client, any engine; every target, browsers included (UNIVERSAL tier) | done |
| `kcoap-transport-ktor-websocket-server` | WebSocket server on the ktor server: standalone on the engine the application passes (no engine dependency) or mounted in an application; JVM, Apple and Linux (SOCKETS tier, no web targets: a browser cannot accept connections) | done |
| `kcoap-testkit` | In-memory datagram network and in-memory TCP/WebSocket connections with fault injection, the stress helper, for tests | done |
| `kcoap-dtls` | DTLS 1.2 engine (and TLS 1.2 stream mode for PSK/RPK over TCP) | interfaces only, not published; engine last (E1) |
| `kcoap-swift-smoke` | The Swift smoke test: a `KcoapSmoke` framework of kcoap-coap for the Apple targets and a Swift script against it, run by the macOS CI job | test only, not published |

Planned modules, none of which exists yet ([target-architecture.md](target-architecture.md) §4.4):

| Module | Scope | When |
|---|---|---|
| kcoap-crypto-pq | Pure-Kotlin ML-KEM and ML-DSA for every target, ML-DSA verification first | E3 |
| kcoap-apple | A curated Swift facade and an XCFramework as a SwiftPM binary target | E4, after the first publish |
| Key-store adapters | Android KeyStore, Secure Enclave and Keychain, non-extractable WebCrypto keys, PKCS#11, as `CryptoBackend` decorators | E7 |
| A Network.framework adapter | UDP, TCP, TLS and multicast on Apple platforms with the system's network policies | E10 |
| A JVM TLS listener | `coaps+tcp` servers on JSSE's `SSLEngine`, with ALPN and client certificates | when ADR 0010's triggers occur |
| A Bouncy Castle `DtlsEngine` | An optional JVM engine, and a differential oracle for kcoap's own | with or after E1 |

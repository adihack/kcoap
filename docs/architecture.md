# Architecture

Status: **built for UDP, TCP and WebSockets, on every target**. The CoAP endpoint with client and
server APIs, OSCORE, EDHOC (including the post-quantum suite), the EDHOC→OSCORE glue, the crypto
backends, the UDP transport and CoAP over TCP and WebSockets (RFC 8323) exist and are cross-tested;
the protocol modules build for the JVM, Apple, Linux and the web (`js`, `wasmJs`) and pass the same
common tests on each. DTLS and TLS for `coaps+tcp` are designed here and come later. This records how
kcoap stays one protocol core while running in mobile apps, browsers and server applications. The
RFC checklist is in `rfc-compliance.md`.

## Module dependencies

```
kcoap-common ─┬─ kcoap-crypto ─┬─ kcoap-crypto-default ── kcoap-crypto-bc (JVM: ML-KEM, ML-DSA)
              │                └─ kcoap-cose ─┐
              ├─ kcoap-cbor ──────────────────┤
              ├─ kcoap-transport ─┬─ kcoap-transport-ktor (UDP, TCP)
              │                   ├─ kcoap-transport-ktor-websocket ── kcoap-transport-ktor-websocket-server
              │                   ├─ kcoap-testkit
              │                   └─ kcoap-dtls
              └─ kcoap-coap ── kcoap-oscore ──┬── kcoap-edhoc-oscore
                       kcoap-edhoc (cbor, cose, crypto) ─┘
```

Protocol modules depend on SPIs, never on a backend or transport: applications choose
`kcoap-crypto-default` or `kcoap-crypto-bc`, and `kcoap-transport-ktor` or their own transport.

## Where the patterns are

| Pattern | Used for |
|---|---|
| SPI interface with a default implementation | `CryptoBackend`, `DatagramTransport`, `ClientMessageSecurity`/`ServerMessageSecurity`, `CredentialResolver`, `LogSink`, `SecurityContextRepository` (`InMemoryContextRepository`) |
| SPI the application must supply | `SequenceNumberStore`: durable before `store()` returns, with recipes per runtime in `kcoap-oscore/README.md`; `EphemeralSequenceNumberStore` only for keys new in this process run. `MutableSecurityContextRepository` for an EDHOC server: `EdhocServer` adds a context per handshake, so production servers supply one with a bound; `InMemoryContextRepository` has none |
| Decorator | `CombinedRequestServer` and `CombinedRequestSecurity` around any `ServerMessageSecurity` and `ClientMessageSecurity`, OSCORE's in practice (RFC 9668); `BouncyCastleCryptoBackend` around the classical backend; a key-store backend around a software one, overriding only the private-key operations (kcoap-crypto README) |
| Strategy | EDHOC ephemeral exchange (Diffie-Hellman or KEM); message codecs per framing |
| Template method | `CoapResource.handleGet()` and siblings; `ObservableResource` |
| State machine over sealed states | `EdhocInitiator`, `EdhocResponder`, the UDP messaging layer |
| Callbacks (`fun interface`) and `Flow` | `EadHandler`, `EdhocSessionListener`, `EndpointEventListener`, Observe as `Flow` |
| Interceptor chain | `MessageInterceptor` for tracing and metrics |
| Builder / DSL | `CoapMessage.Builder`, `OptionSet.Builder`, the server's resource DSL, `LogConfig.Builder` |

## Layers

RFC 8613 Figure 1 already draws the stack. kcoap follows it and adds the channel-security layer:

```
App API            CoapClient / CoapServer: suspend calls, Flow for Observe, streamed bodies
Request/Response   tokens, Observe, inner Block-wise, Echo / Request-Tag, EDHOC resource
OSCORE             protect / verify, identical on every transport           (end to end)
Outer Block-wise   only when a proxy has to fragment
Messaging          UdpMessaging:      CON/NON/ACK/RST, MID dedup, retransmission, NSTART
                   ReliableMessaging: CSM, Ping/Pong, Release/Abort, BERT
Framing            UDP header | TCP/TLS length framing | WebSocket (Len = 0)
Channel security   DTLS 1.2 over datagrams | TLS over byte streams          (hop by hop)
──────────────── transport interface ────────────────
Adapters           one per runtime capability or library, thin
```

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
  instead of sending plaintext; plaintext is an explicit choice (`allowUnprotected`, the EDHOC path).

## The CoAP endpoint as built

One `CoapEndpoint` runs one exchange layer over every transport it was given, through an internal link per
peer ([ADR 0005](adr/0005-peer-link-seam.md)). The layers map onto code like this (`kcoap-coap`):

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
§4.3, §4.4); an event that does not fit is counted as lost, so the receive loop never waits for a slow
driver. A Non-confirmable body holds one NSTART slot through a `BodyLease`, queued like a request and
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
decision. `CoapServer.close()` on a shared endpoint cancels its running handlers and takes no new
observers; a request on a connection whose handler it cancelled before the answer gets 5.03, since RFC
8323 §3.3 has every request on a connection answered.

**Resource bounds.** Over UDP a source address proves nothing (RFC 7252 §11.4), and §11.3 warns that
answering spoofed traffic turns a server into an amplifier against the address it names. Everything a
peer can make the endpoint keep or do is therefore bounded, with defaults sized for servers. At a bound
the endpoint refuses only the extra work, with an answer at most a few bytes larger than what it
received (a refusal's Max-Age), keeps serving what it has, and reports `EndpointEvent.LimitReached`:

| Bound | Default | Beyond it |
|---|---|---|
| `CoapEndpointConfig.maxDeduplicationEntries` | 50 000 | a new message is dropped without state or answer; duplicates are still answered (§4.5) |
| `CoapEndpointConfig.maxConcurrentRequests` | 256 | a CON gets a piggybacked 5.03 with Max-Age, a NON is dropped, a connection gets 5.03 |
| `CoapEndpointConfig.maxExchangesPerPeer` | 256 | a request fails with `IDENTIFIERS_EXHAUSTED`; a separate response or notification queued behind NSTART is not sent |
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
its tests need neither a transport nor a clock. It is `internal` for now: the plan's second API level, a
public sans-IO engine for Netty or Vert.x event loops, would also need the exchange, block-wise and
Observe layers in that form, which they are not yet.

**Time and randomness.** Every timer reads `CoapEndpointConfig.timeSource` and waits with the delays of
the endpoint scope's dispatcher, so the time source must advance with that dispatcher's clock:
`TimeSource.Monotonic` with a real dispatcher, and under `runTest` `testScheduler.timeSource`, with which
a 247-second lifetime passes in an instant. The endpoint warns once when it sees the two disagree.
Request timeouts wait on the same dispatcher clock. Retransmission jitter and the first Message ID come
from an injected `Random`; tokens from a `TokenGenerator` backed by the platform CSPRNG (through
`kotlin.uuid`), with per-session sequence numbers on DTLS endpoints (RFC 9175 §4.2), one generator per
endpoint unless the configuration names one.

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

**Observability.** A `MessageInterceptor` chain sees every message at the wire, once per message;
`EndpointEventListener` gets retransmissions, timeouts, resets, duplicates, malformed datagrams,
rejections, session ends and observer changes. Logging follows `code-style.md` under the tags
`coap.message`, `coap.exchange`, `coap.server`, `coap.client` and `transport`.

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
    public val schemes: Set<CoapScheme>
    public suspend fun connect(peer: Endpoint): Connection
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

Everything above the messaging layer sees exchanges and links, never sockets. There is no URI scheme
registry ([ADR 0004](adr/0004-no-scheme-registry.md)): the client's `EndpointResolver` turns a URI into an
`Endpoint` (`EndpointResolver.Default` takes IP literals for every scheme, `localhost` for datagrams, and
leaves host names of RFC 8323 schemes to the connector), `CoapScheme` maps each scheme to a `TransportKind`
and a channel security, and the endpoint picks the link by the kind of `Endpoint`: its datagram transport for
`coap` (and `coaps` once DTLS exists), the `Connector` whose `schemes` include `coap+tcp`, `coaps+tcp`,
`coap+ws` or `coaps+ws`. A scheme nothing serves fails with `CoapException(TRANSPORT_UNAVAILABLE)`.

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

What the DTLS feature itself adds later: the `DtlsEngine` implementation, a `DatagramSecurity` that
drives it, the RFC 6920 identifier and RFC 7252 §9.1.3.3 name check, the TLS 1.2 PRF, and the DER↔raw
ECDSA conversion. ECDH and ECDSA on P-256 arrive earlier with EDHOC. kcoap-dtls is built and tested but
not published until then.

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

### Server side

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

RFC 8323 §9.1 applies RFC 7925 to TLS too, with PreSharedKey, RawPublicKey or Certificate depending on
the device, and RFC 7525 recommendations for less constrained deployments.

- **Certificate mode** uses the platform TLS: JSSE or Conscrypt (through Ktor or Netty),
  Network.framework, and the browser for `wss://`. This is the only option in browsers.
- **PSK and RawPublicKey over TCP** need kcoap's own engine in stream mode, inside a `coaps+tcp`
  connector and listener like any other TLS (docs/adr/0003-tls-in-connectors.md). The TLS 1.2 handshake
  is the same as DTLS 1.2 minus cookies, fragmentation and retransmission, so this is a small addition.

### Later: DTLS 1.3

RFC 9147 is optional for now. Deployed CoAP devices use DTLS 1.2, libcoap supports DTLS 1.3 only with
wolfSSL, and the TLS/DTLS 1.3 IoT profile (draft-ietf-uta-tls13-iot-profile) is still a draft. The
engine interface does not change.

## Streams

The RFCs have three things that behave like streams. They are abstracted at different layers:

| What | RFC | Layer | Kotlin shape |
|---|---|---|---|
| CoAP over TCP, TLS, WebSocket | RFC 8323 | transport | `ByteStreamConnection`, `MessageConnection` |
| Observe notifications | RFC 7641 | API | `Flow<Response>` |
| Large bodies | RFC 7959, RFC 9177 | API | streamed body (kotlinx-io `Source` / `Sink`) |

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

| Platform | UDP | DTLS | TCP / TLS | WebSocket | HTTP (RFC 8613 §11) |
|---|---|---|---|---|---|
| JVM server | NIO or Netty | kcoap (or BC) | Netty / JSSE | Ktor server / Netty | Ktor |
| Android | ktor-network / NIO | kcoap (or BC) | ktor-network | Ktor client (OkHttp) | Ktor client |
| iOS | Network.framework or ktor-network | kcoap | Network.framework | Ktor client (Darwin) | Ktor client |
| Browser (JS / Wasm) | **no** | **no** | **no** | Ktor client (JS) | `fetch` |
| Node.js | `dgram` | kcoap | `net` / `tls` | Ktor client | `fetch` |

Built so far: UDP and TCP on ktor-network (`kcoap-transport-ktor`) for the JVM, Apple and Linux; the
WebSocket client on the ktor client (`kcoap-transport-ktor-websocket`), which takes any engine, so the
browser needs no other adapter; and the WebSocket server on the ktor server
(`kcoap-transport-ktor-websocket-server`). TLS for TCP is the open item.

Multicast needs native APIs: NIO `join()` on the JVM, Network.framework plus Apple's multicast
entitlement on iOS. iOS also needs the Local Network permission for LAN peers.

## Browsers

kcoap runs in browsers and in Node.js as the `js` and `wasmJs` targets, from the same common code as
everywhere else. What works there today:

| Works in the browser now | Comes with the WebSocket transport (RFC 8323, in progress) |
|---|---|
| CoAP messages, options, URIs, the UDP and RFC 8323 codecs | `coap+ws` / `coaps+ws` to a proxy, the reliable messaging layer (CSM, Ping/Pong) |
| OSCORE: contexts, protect/verify, replay windows, Observe | OSCORE end to end through the proxy, as RFC 8613 §11 intends |
| EDHOC: all methods, suites 0–3, 6 and 24; EDHOC→OSCORE, the combined request | EDHOC over CoAP to a real device |
| The client and server API over a `DatagramTransport` the application supplies, `kcoap-testkit` | — |

`kcoap-transport-ktor` builds no web targets: browsers have no UDP. (ktor-network does reach UDP and
TCP on Node.js through `node:dgram` and `node:net`, so a Node.js target for it is possible; a browser
WebSocket transport needs a module that builds for the web, since this one depends on ktor-network.)

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

- libcoap's `coap-server` is such a proxy: it speaks WebSockets and forwards between all six CoAP
  schemes. It doubles as the interop setup.
- DTLS and TLS end at the proxy. End-to-end security for browsers comes only from OSCORE + EDHOC,
  which do not protect the messaging layer or the token and therefore survive the translation.
- RFC 8613 §11 carries OSCORE over HTTP directly: POST, `OSCORE` header, `application/oscore`.

## Servers

- **Two API levels from one core**: a sans-IO engine (bytes and time in, bytes and timers out) for
  embedding in a Netty or Vert.x event loop, and a coroutine endpoint on top.
- **One engine per socket**, no locks inside. On Linux, several sockets with `SO_REUSEPORT`; the kernel
  keeps a client on the same socket.
- **State keyed by security context, not by address**: OSCORE context, or DTLS session and CID.
  Combined with a shared store this survives NAT rebinding and node changes.
- Rate limiting (RFC 8516 Too Many Requests) and amplification mitigation (RFC 9175 Echo).

## Extension points

- **Clock and timers**, injectable, so tests run on virtual time.
- **Randomness and ephemeral keys**, injectable, for RFC 9529 and DTLS trace replay.
- **Storage**: OSCORE contexts (`SecurityContextRepository`, and `MutableSecurityContextRepository` where
  EDHOC adds them), persisted sequence numbers (RFC 8613 Appendix B.1.1), replay windows, EDHOC state
  between messages, DTLS session tickets.
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
- **Logging and metrics** as hooks, no logging framework dependency.

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
   decompression in kcoap-crypto's `Sec1`). Tests skip by capability too.

## Modules

| Module | Scope | State |
|---|---|---|
| `kcoap-transport` | Transport interfaces, `Endpoint`, `PeerIdentity`, `CoapScheme`, `IpLiteral`, security interfaces | interfaces |
| `kcoap-coap` | Message model, codecs, the endpoint with client and server over UDP and RFC 8323 connections, block-wise and BERT, Observe | UDP, TCP and WebSocket done |
| `kcoap-oscore` | RFC 8613 behind the message-security SPI | done |
| `kcoap-edhoc` | RFC 9528 Initiator and Responder, DH and KEM suites | done |
| `kcoap-edhoc-oscore` | RFC 9528 Appendix A, RFC 9668 | done |
| `kcoap-testkit` | In-memory datagram network and in-memory TCP/WebSocket connections with fault injection, for tests | done |
| `kcoap-dtls` | DTLS 1.2 engine (and TLS 1.2 stream mode for PSK/RPK over TCP) | placeholders, not published; engine last |
| `kcoap-transport-ktor` | UDP and TCP on ktor-network; JVM, Apple and Linux (no web targets: ktor-network) | UDP and TCP done; TLS for `coaps+tcp` planned |
| `kcoap-transport-ktor-websocket` | WebSocket client on the ktor client, any engine; every target, browsers included (UNIVERSAL tier) | done |
| `kcoap-transport-ktor-websocket-server` | WebSocket server on the ktor server: standalone on the engine the application passes (no engine dependency) or mounted in an application; JVM, Apple and Linux (SOCKETS tier, no web targets: a browser cannot accept connections) | done |
| `kcoap-transport-netty` | JVM server: epoll, `SO_REUSEPORT`, multicast | planned |
| `kcoap-transport-apple` | Network.framework: UDP, multicast, TLS | planned |
| `kcoap-dtls-bc` | Optional Bouncy Castle `DtlsEngine` for the JVM | planned |

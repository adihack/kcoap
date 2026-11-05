# kcoap-coap

CoAP itself: the message model and codecs, and the endpoint stack with client and server APIs for
CoAP over UDP and over reliable transports (TCP, WebSockets; RFC 8323). Common Kotlin, no I/O of its own: transports plug in through `kcoap-transport`.

## Packages

| Package | What |
|---|---|
| `dev.kcoap.coap` | `CoapMessage`, `CoapCode`, `OptionSet` and the option registry, `BlockOption`, `CoapUri`, `CoapRequest`, `CoapResponse`, `CoapException`, `LinkFormat` / `WebLink` (RFC 6690) |
| `dev.kcoap.coap` (signaling) | `SignalingOptions`: the RFC 8323 §5.2 signaling option registry, per signaling code |
| `dev.kcoap.coap.codec` | `UdpMessageCodec` (RFC 7252 §3), `ReliableMessageCodec` and `ReliableStreamDecoder` (RFC 8323 §3.2, §4.2) |
| `dev.kcoap.coap.security` | The message-security SPI OSCORE implements (`kcoap-oscore`) |
| `dev.kcoap.coap.stack` | `CoapEndpoint`, its config and `TransmissionParameters`, `TokenGenerator`, `MessageInterceptor`, `EndpointEvent`; internally the sans-IO `UdpMessaging` state machine and the exchange layer |
| `dev.kcoap.coap.server` | `CoapServer`, `CoapResource`, `ObservableResource`, `RequestContext`, `ResourceAttributes`, the `ResourceBuilder` DSL |
| `dev.kcoap.coap.client` | `CoapClient`, `CoapClientConfig`, `EndpointResolver`, `ObserveCancellation` |

```kotlin
val server = CoapServer(KtorUdpTransport.bind(port = 5683), scope) {
    resource("hello") { get { CoapResponse.content("Hello") } }
}
val client = CoapClient(KtorUdpTransport.bind(), scope)
println(client.get("coap://127.0.0.1/hello").payloadAsText())
```

More in `docs/guide-client-server.md`.

## Implemented

The stack follows RFC 8613 Figure 1: application, then request/response (tokens, Observe, Block-wise),
then message security (OSCORE through the SPI), then messaging, then the transport.

- **Messaging, RFC 7252 §4** (`UdpMessaging`, sans-IO): Message IDs not reused towards a peer within
  EXCHANGE_LIFETIME; retransmission with exponential back-off from a random initial timeout inside
  MAX_TRANSMIT_SPAN; deduplication over EXCHANGE_LIFETIME / NON_LIFETIME answering duplicate CONs with
  the same ACK or RST; Empty ACKs for slow requests; RST for CoAP ping, reserved codes and malformed
  CONs, silent drop for malformed NONs and invalid ACK/RST; NSTART per peer, with Non-confirmable
  requests holding their slot at PROBING_RATE; failing instead of retransmitting across ended DTLS
  sessions (§9.1.1). All parameters in `TransmissionParameters`.
- **Exchanges, RFC 7252 §5.2–5.3**: random 8-byte tokens from the platform CSPRNG, sequence-number
  tokens on DTLS sessions (RFC 9175 §4.2); matching by peer `Endpoint` equality and token; piggybacked,
  separate and Non-confirmable responses; response timeouts; cancellation through coroutines; the
  message-security SPI around each message. A separate response is answered only after it was verified
  and its critical options checked: one with an unknown critical option gets a Reset and fails the
  request (§5.4.1). For Q-Block (RFC 9177, being wired up) exchanges can take several responses on one
  token into a per-body inbox, and a body lease holds one NSTART slot for a whole Non-confirmable body.
- **Server**: resource tree by Uri-Path with template methods (`handleGet` … `handleIPatch`, 4.05 by
  default) or a DSL; 4.04; 4.02 with a diagnostic for unrecognized critical options, wrong lengths and
  repetitions (§5.4.1, §5.4.3, §5.4.5), rejection for NON; 4.06 for Accept (§5.10.4); 5.05 for
  Proxy-Uri / Proxy-Scheme (§5.10.2); 5.00 for failing handlers; conditional-request helper (§5.10.8);
  `/.well-known/core` with `rt`, `if`, `ct`, `sz`, `title`, `obs` and RFC 6690 §4.1 filtering;
  No-Response (RFC 7967); the request context with the peer, its `PeerIdentity` and the
  `SecurityIdentity`.
- **Observe, RFC 7641**: server observers keyed by endpoint and token, 24-bit sequence numbers, at
  most one outstanding notification per client (NSTART) with superseding at the next timeout (§4.5.2),
  NON notifications rate-limited with a CON at least every 24 hours, observers removed on RST, timeout,
  non-2.xx notification, deregistration, ended session and resource removal; client observations shared
  per target resource (§3.1), ordered by §3.4, cancelled by deregistration or by forgetting.
- **Block-wise, RFC 7959**: client Block1 uploads following the server's preferred size and the 4.13
  hints, Block2 downloads with ETag checks and restarts, early negotiation, Size1/Size2; server atomic
  Block1 assembly with 2.31 / 4.08 / 4.13 + Size1, Block2 from a cached representation with an ETag, also
  for notifications (§2.6); block sizes from the transport's `maxDatagramSize`. RFC 9175 Request-Tags for
  concurrent Block1 operations, and server state keyed by them.
- **RFC 9175 Echo**: a 4.01 with Echo is answered once with the Echo value; the security layer can
  answer with a challenge instead of the resource (RFC 8613 Appendix B.1.2). Against amplification the
  server verifies a client's address before a response larger than three times its request (§2.4 item
  3, §2.6): a client that neither OSCORE nor DTLS authenticated, on a datagram transport, gets a 4.01
  with an Echo value instead, piggybacked or Non-confirmable, and returning the value verifies the
  address for an hour. `CoapServerConfig.amplification` checks Q-Block2 bursts by default (none are sent
  yet), every response with `ALL_RESPONSES` (which also sends separate responses to an unverified address
  Non-confirmable, and keeps each POST's or PATCH's response for its repeat), or nothing.
- **Interceptors and events**: a `MessageInterceptor` chain at the wire, an `EndpointEventListener`.
- **Lifecycle**: one terminal transition however the endpoint ends (`close()`, cancellation of its
  scope, a failed datagram transport), with `closeCause` (`CLOSED` or `TRANSPORT_FAILED`) and
  `awaitClosed()`; open exchanges fail, `observe` collectors get a terminal failure, later calls fail at
  once. Receiving starts when a server attaches or the first request goes out. A send the transport
  refuses fails at once with `TRANSPORT_FAILED`. The server pipeline is one failure boundary: whatever
  throws, the client gets an answer (5.00 with Max-Age 0 unless the security layer says otherwise), a
  failing notification removes its observer, and nothing escapes to the application's exception
  handler. A request on a connection whose handler was cancelled, by `CoapServer.close()` for example,
  gets 5.03 (RFC 8323 §3.3).
- **Resource bounds, RFC 7252 §11.3**: everything a peer can make the endpoint keep is bounded, and a
  bound reports `EndpointEvent.LimitReached`. `CoapEndpointConfig.maxDeduplicationEntries` (50 000; new
  messages dropped silently), `maxConcurrentRequests` (256; piggybacked 5.03 with Max-Age),
  `maxExchangesPerPeer` (256; `IDENTIFIERS_EXHAUSTED`), `CoapServerConfig.maxObservers`,
  `maxObserversPerPeer` and `maxObserversPerResource` (10 000, 32 and 1 000; served without Observe,
  RFC 7641 §4.1), `ReliableTransportConfig.maxPendingConnections` (128; accepted connections awaiting
  their CSM) and `maxConnections` (1 024; accepted connections beyond it are closed). Non-confirmable
  notifications to unauthenticated observers are interspersed with Confirmable ones (RFC 7641 §7).
- **Threads**: every public type is safe to use from any number of coroutines on any dispatcher; the
  endpoint runs on the dispatcher of the scope it is given, so on Android or iOS give it one such as
  `lifecycleScope + Dispatchers.Default`. Under `runTest`, give it `testScheduler.timeSource`: timers
  wait on the dispatcher's clock and must read the same one (the endpoint warns when they disagree).

- **CoAP over reliable transports, RFC 8323** (`ReliableMessaging`, sans-IO, one per connection, and
  the connection management around it): CSM first from both sides, the peer's required first and
  within `csmTimeout`, Max-Message-Size and Block-Wise-Transfer cumulative (§3.3, §5.3);
  Max-Message-Size enforced both ways; Ping/Pong with the Ping's token and Custody (§5.4); Release on
  idle client connections and at shutdown, draining and Hold-Off when received (§5.5); Abort with
  Bad-CSM-Option for missing or invalid CSMs, format errors, oversize messages and unknown critical
  signaling options (§5.2, §5.6); keep-alive pings; requests both ways, 5.01 without a server (§3.3);
  connection loss fails exchanges and removes observers (§3.4, §7.4); BERT in both directions (§6);
  Observe without message types, values ignored on receipt, explicit deregistration (§7). Clients
  connect on demand through `Connector`s, one connection per peer; servers accept from
  `ConnectionListener`s. Settings in `ReliableTransportConfig`.

## Not yet

DTLS (`coaps`, last on the roadmap; the endpoint already follows the rules in `docs/architecture.md`),
TLS for `coaps+tcp` (no TLS connector yet; `coaps+ws` works on the ktor engine's TLS), multicast, proxying and caching, Hop-Limit, client
re-registration when Max-Age expires (optional in RFC 7641 §3.3.1), server-side Echo freshness checks
(RFC 9175 §2.3), rate limiting with 4.29 (RFC 8516), Q-Block (RFC 9177).

## Verified by

- RFC 8323 §3.2 Figure 5 and §5.7 Figures 11 and 12 byte for byte; `ReliableMessagingTest` and
  `SignalTest` per section without sockets or clocks; `ReliableTransportTest` end to end over
  `kcoap-testkit`'s in-memory connections on virtual time; `ReliableLoopbackTest` over real TCP and
  WebSocket sockets; libcoap in both directions over `coap+tcp` and `coap+ws` (`interop/README.md`).

- RFC 8613 Appendix C messages through the UDP codec, and the RFC 7252, 7641, 7959, 6690, 9175 rules
  as unit tests named after their sections, on virtual time over `kcoap-testkit`'s simulated network,
  including loss, duplication and reordering.
- The message-security SPI with a fake OSCORE-like protection (inner options and code moved into the
  payload): protection per block and per notification, unprotected 4.01 / 4.00 errors with Max-Age 0,
  structured error bodies, challenges.
- Live interop with libcoap v4.3.5b in both directions (`src/jvmTest/.../interop`, see
  `interop/README.md`).
- Lifecycle, cancellation, failure boundaries and every resource bound per test class
  (`EndpointLifecycleTest`, `CancellationCleanupTest`, `FailureBoundaryTest`, `ResourceBoundsTest`,
  `TimeSourceContractTest`, `AmplificationTest`, `AddressVerificationTest`), on virtual time; `ConcurrencyStressTest` on real threads through
  `kcoap-testkit`'s `Stress`, on the JVM and Kotlin/Native: close racing requests and collectors, the
  observer registry, the connection pool, requests racing an idle Release.
- Line coverage 98% (`./gradlew :kcoap-coap:koverXmlReport`).

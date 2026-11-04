# kcoap-coap

CoAP itself: the message model and codecs, and the endpoint stack with client and server APIs for
CoAP over UDP. Common Kotlin, no I/O of its own: transports plug in through `kcoap-transport`.

## Packages

| Package | What |
|---|---|
| `dev.kcoap.coap` | `CoapMessage`, `CoapCode`, `OptionSet` and the option registry, `BlockOption`, `CoapUri`, `CoapRequest`, `CoapResponse`, `CoapException`, `LinkFormat` / `WebLink` (RFC 6690) |
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
  message-security SPI around each message.
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
  answer with a challenge instead of the resource (RFC 8613 Appendix B.1.2).
- **Interceptors and events**: a `MessageInterceptor` chain at the wire, an `EndpointEventListener`.

## Not yet

DTLS (`coaps`, last on the roadmap; the endpoint already follows the rules in `docs/architecture.md`),
RFC 8323 reliable transports (codec only), multicast, proxying and caching, Hop-Limit, client
re-registration when Max-Age expires (optional in RFC 7641 §3.3.1), server-side Echo freshness checks
and amplification mitigation (RFC 9175 §2.4), Q-Block (RFC 9177).

## Verified by

- RFC 8613 Appendix C messages through the UDP codec, and the RFC 7252, 7641, 7959, 6690, 9175 rules
  as unit tests named after their sections, on virtual time over `kcoap-testkit`'s simulated network,
  including loss, duplication and reordering.
- The message-security SPI with a fake OSCORE-like protection (inner options and code moved into the
  payload): protection per block and per notification, unprotected 4.01 / 4.00 errors with Max-Age 0,
  structured error bodies, challenges.
- Live interop with libcoap v4.3.5b in both directions (`src/jvmTest/.../interop`, see
  `interop/README.md`).
- Line coverage 98% (`./gradlew :kcoap-coap:koverXmlReport`).

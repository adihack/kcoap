# Architecture

Status: **built for UDP**. The CoAP endpoint with client and server APIs, OSCORE, EDHOC (including the
post-quantum suite), the EDHOC→OSCORE glue, the crypto backends and the UDP transport exist and are
cross-tested; DTLS, TCP and WebSocket transports and the browser target are designed here and come
later. This records how kcoap stays one protocol core while running in mobile apps, browsers and
server applications. The RFC checklist is in `rfc-compliance.md`.

## Module dependencies

```
kcoap-common ─┬─ kcoap-crypto ─┬─ kcoap-crypto-default ── kcoap-crypto-bc (JVM: ML-KEM, ML-DSA)
              │                └─ kcoap-cose ─┐
              ├─ kcoap-cbor ──────────────────┤
              ├─ kcoap-transport ─┬─ kcoap-transport-ktor
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
| SPI interface with a default implementation | `CryptoBackend`, `DatagramTransport`, `ClientMessageSecurity`/`ServerMessageSecurity`, `CredentialResolver`, `SequenceNumberStore`, `LogSink` |
| Decorator | `CombinedRequestServer` and `CombinedRequestSecurity` around OSCORE (RFC 9668); `BouncyCastleCryptoBackend` around the classical backend |
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
Adapters           one per platform, thin
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

The application sees the identities of both layers and authorizes on either.

## The CoAP endpoint as built

For CoAP over UDP the layers map onto code like this (`kcoap-coap`):

```
CoapClient / CoapServer       client.*, server.*: suspend calls, Flow for Observe, resource tree + DSL
Block-wise, Observe           BlockwiseClient / BlockwiseServer, ClientObservation / ObservationRegistry
Exchanges                     CoapEndpoint + ClientExchange: tokens, matching by (Endpoint, token)
Message security              ClientMessageSecurity / ServerMessageSecurity, called per message
Messaging                     UdpMessaging: sans-IO, internal
Transport                     DatagramTransport: KtorUdpTransport, InMemoryTransport, later DTLS
```

Block-wise and Observe sit above message security, so OSCORE protects each block and each notification
on its own (inner Block-wise, RFC 8613 §4.1.3.4.1); outer Block-wise for proxies is not built.

**One endpoint per socket, one lock.** `CoapEndpoint` owns the messaging state machine and the exchange
registry behind a single `Mutex` that is never held across a suspension: a call takes the lock, runs the
state machine, takes its effects (datagrams to send, messages to deliver, outcomes of transmissions),
releases the lock and only then performs them. Request handlers, message security and transports
therefore run without the lock; each inbound request gets its own coroutine. The receive loop and a
timer loop run in the scope the endpoint was given.

**Sans-IO where it pays off.** `UdpMessaging` is the part with the subtle timing rules (back-off,
deduplication lifetimes, NSTART, superseding), so it takes datagrams and time and returns effects, and
its tests need neither a transport nor a clock. It is `internal` for now: the plan's second API level, a
public sans-IO engine for Netty or Vert.x event loops, would also need the exchange, block-wise and
Observe layers in that form, which they are not yet.

**Time and randomness.** Every timer reads `CoapEndpointConfig.timeSource` and waits with coroutine
delays, so under `runTest` with `testScheduler.timeSource` a 247-second lifetime passes in an instant.
Retransmission jitter and the first Message ID come from an injected `Random`; tokens from a
`TokenGenerator` backed by the platform CSPRNG (through `kotlin.uuid`), with per-session sequence
numbers on DTLS endpoints (RFC 9175 §4.2).

**The DTLS readiness rules, as met:**

1. Matching and deduplication use `Endpoint` equality only: Message ID state, dedup cache, exchanges,
   observers, block-wise state and Request-Tags are all keyed by `Endpoint`.
2. `DatagramEvent.SessionEnded` (or `SessionEndedException` from a send) fails the pending Confirmable
   messages of that endpoint without retransmitting, fails its exchanges and client observations
   (`CoapException.Reason.SESSION_ENDED`), removes its server observers and block-wise state, and drops
   its deduplication, Message ID and token state.
3. Block sizes and the message size limit come from `DatagramTransport.maxDatagramSize`, minus a
   configurable reserve when message security is on.
4. `RequestContext` carries the `PeerIdentity` of an `Endpoint.Secure` peer next to the OSCORE
   `SecurityIdentity`.
5. One `TimeSource` per endpoint drives every timer.
6. There is no multicast yet, so nothing is sent to a group through any transport.

**Observability.** A `MessageInterceptor` chain sees every message at the wire, once per message;
`EndpointEventListener` gets retransmissions, timeouts, resets, duplicates, malformed datagrams,
rejections, session ends and observer changes. Logging follows `code-style.md` under the tags
`coap.message`, `coap.exchange`, `coap.server`, `coap.client` and `transport`.

**Tests.** `kcoap-testkit`'s `InMemoryNetwork` simulates loss, duplication, latency and reordering on
virtual time; the JVM tests also run against libcoap over real UDP (`interop/README.md`).

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

public interface ByteStreamConnection {               // TCP, TLS
    public val remote: Endpoint
    public val peerIdentity: PeerIdentity
    public suspend fun read(into: ByteArray, offset: Int, length: Int): Int
    public suspend fun write(bytes: ByteArray)
    public suspend fun close()
}

public interface MessageConnection {                  // WebSocket
    public val remote: Endpoint
    public val peerIdentity: PeerIdentity
    public suspend fun send(message: ByteArray)
    public suspend fun receive(): ByteArray?
    public suspend fun close()
}

public sealed interface Endpoint                     // Udp, Tcp, WebSocket, Opaque, Secure
public sealed interface PeerIdentity                 // None, PreSharedKey, RawPublicKey, Certificate
```

On reliable transports one messaging-layer instance serves one connection, so tokens are scoped to
the connection and `Endpoint.Tcp` / `Endpoint.WebSocket` only describe the peer.

Everything above the messaging layer sees exchanges, never sockets. A URI scheme registry maps `coap`,
`coaps`, `coap+tcp`, `coaps+tcp`, `coap+ws` and `coaps+ws` to a transport factory.

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
| `DatagramSecurity` (coaps) and `StreamSecurity` (PSK/RPK over TCP) interfaces with their contracts | `kcoap-transport` |
| `CoapScheme`: the six schemes, default ports, transport kind, channel security, ALPN `coap` | `kcoap-transport` |
| `DtlsEngine` and `DtlsEffect`: the sans-IO engine interface | `kcoap-dtls` |
| `DtlsCipherSuite`: the two mandatory suites, CCM-8 parameters, record overhead incl. CID | `kcoap-dtls` |
| `DtlsConfig` with RFC 7925 defaults and per-mode validation | `kcoap-dtls` |
| `DtlsCredentials`: `PskStore`, `DtlsSigner` (key stays in KeyStore/Enclave/HSM), `RawPublicKeyTrust` | `kcoap-dtls` |
| `CertificateValidator`, shared with EDHOC | `kcoap-crypto` |

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
drives it, the stream-mode TLS variant, the RFC 6920 identifier and RFC 7252 §9.1.3.3 name check, the
TLS 1.2 PRF, and the DER↔raw ECDSA conversion. ECDH and ECDSA on P-256 arrive earlier with EDHOC.

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
- **PSK and RawPublicKey over TCP** need kcoap's own engine in stream mode. The TLS 1.2 handshake is the
  same as DTLS 1.2 minus cookies, fragmentation and retransmission, so this is a small addition.

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
correlated by a prefixed connection identifier (RFC 9528 Appendix A.2).

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

Multicast needs native APIs: NIO `join()` on the JVM, Network.framework plus Apple's multicast
entitlement on iOS. iOS also needs the Local Network permission for LAN peers.

## Browsers

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
- **Storage**: OSCORE contexts, persisted sequence numbers (RFC 8613 Appendix B.1.1), replay windows,
  EDHOC state between messages, DTLS session tickets.
- **Credentials**: resolvers for EDHOC credentials, PSKs and raw public keys; one `CertificateValidator`
  shared by EDHOC (`x5chain`) and DTLS/TLS, backed by the platform (JVM `CertPathValidator`, Apple
  `SecTrust`).
- **EAD handlers** for EDHOC.
- **Logging and metrics** as hooks, no logging framework dependency.

## Decisions taken for multiplatform

1. **`CryptoBackend` is `suspend`**, because WebCrypto is promise based. Done.
2. **AES-CCM is common code on the platform AES block**; on WebCrypto the block will come from AES-CTR
   (encrypting a zero block with the input as the counter yields the encrypted input).
3. **ECDH, ECDSA, EdDSA, KEMs and signatures are in the SPI**, with opaque private-key handles. COSE
   uses raw `r || s` ECDSA signatures; DTLS will need DER and converts.
4. **`js` and `wasmJs` targets** are next, without DTLS adapters; the common code already compiles
   against the common standard library only.

## Modules

| Module | Scope | State |
|---|---|---|
| `kcoap-transport` | Transport interfaces, `Endpoint`, `PeerIdentity`, `CoapScheme`, `IpLiteral`, security interfaces | interfaces |
| `kcoap-coap` | Message model, codecs, the UDP endpoint with client and server, block-wise, Observe | UDP done |
| `kcoap-oscore` | RFC 8613 behind the message-security SPI | done |
| `kcoap-edhoc` | RFC 9528 Initiator and Responder, DH and KEM suites | done |
| `kcoap-edhoc-oscore` | RFC 9528 Appendix A, RFC 9668 | done |
| `kcoap-testkit` | In-memory datagram network with fault injection, for tests | done |
| `kcoap-dtls` | DTLS 1.2 engine (and TLS 1.2 stream mode for PSK/RPK over TCP) | placeholders; engine last |
| `kcoap-transport-ktor` | UDP, TCP, WebSocket and HTTP on all targets except browser UDP/TCP | UDP done; TCP, WebSocket, HTTP planned |
| `kcoap-transport-netty` | JVM server: epoll, `SO_REUSEPORT`, multicast | planned |
| `kcoap-transport-apple` | Network.framework: UDP, multicast, TLS | planned |
| `kcoap-dtls-bc` | Optional Bouncy Castle `DtlsEngine` for the JVM | planned |

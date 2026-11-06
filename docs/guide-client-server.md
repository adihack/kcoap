# Guide: CoAP clients and servers

How to serve and request CoAP resources with kcoap over UDP, TCP and WebSockets. Every example here is
compiled and run (on free ports instead of 5683) by
`kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/GuideExamplesTest.kt`.

Modules: `kcoap-coap` (the stack, which brings the message model of `kcoap-coap-message` along),
`kcoap-transport-ktor` (UDP and TCP sockets),
`kcoap-transport-ktor-websocket` and `kcoap-transport-ktor-websocket-server` (WebSockets), and for tests
`kcoap-testkit` (a simulated network and in-memory connections).

## A server

```kotlin
suspend fun startServer(scope: CoroutineScope): CoapServer {
    val clock = object : ObservableResource("time") {
        override val attributes = ResourceAttributes(resourceTypes = listOf("ticks"), title = "Clock")
        override suspend fun handleGet(context: RequestContext) =
            CoapResponse.content(Clock.System.now().toString()).withOptions { maxAge(1) }
    }
    var stored = "nothing yet"
    val server = CoapServer(KtorUdpTransport.bind("127.0.0.1", 5683), scope) {
        resource("hello") {
            attributes = ResourceAttributes(title = "Greeting")
            get { CoapResponse.content("Hello, $peer") }
        }
        resource("store") {
            get { CoapResponse.content(stored) }
            put {
                stored = payloadAsText()
                CoapResponse.changed()
            }
        }
        resource("slow") {
            get {
                delay(2.seconds) // an Empty ACK goes out after 1 s; the response follows separately
                CoapResponse.content("finally")
            }
        }
        add(clock)
    }
    scope.launch {
        while (isActive) {
            delay(1.seconds)
            clock.notifyObservers()
        }
    }
    return server
}
```

- Resources are a tree by Uri-Path. Define them with the DSL, or subclass `CoapResource` and override
  `handleGet`, `handlePost`, `handlePut`, `handleDelete`, `handleFetch`, `handlePatch` or `handleIPatch`;
  other methods answer 4.05.
- A handler gets a `RequestContext`: the `method`, `path`, `query`, `options` and `payload` of the
  (verified, reassembled) request, the `peer` endpoint, its DTLS `peerIdentity`, the OSCORE
  `securityIdentity`, and `acknowledge()` to promise a separate response early. Like the client's
  `CoapRequest` it leaves out the messaging-layer fields (type, Message ID, token), which differ by transport.
- `CoapServerConfig.maxRequestBodySize` (1 MiB) bounds the request bodies the server assembles; larger ones
  get 4.13 with Size1 (RFC 7959 §2.9.3).
- Large responses and request bodies go block-wise automatically, in blocks that fit the transport's
  datagram size. `/.well-known/core` lists every resource with handlers that the caller may use (see
  [Message security](#message-security)), with `obs` for observable ones.
- An `ObservableResource` (or `observable()` in the DSL) is observed through GET or FETCH with Observe;
  call `notifyObservers()` whenever its state changes.

## A client

```kotlin
suspend fun useServer(scope: CoroutineScope) {
    val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope)
    val server = "coap://127.0.0.1:5683"

    println(client.get("$server/hello").payloadAsText())
    client.put("$server/store", "on".encodeToByteArray(), ContentFormat.TEXT_PLAIN)
    println(client.get("$server/store") { accept(ContentFormat.TEXT_PLAIN) }.payloadAsText())
    println(client.get("$server/slow").payloadAsText())

    val links = client.discover(server, "rt=ticks")
    println(links.map { it.target })

    client.observe("$server/time").take(3).collect { println("time: ${it.payloadAsText()}") }
    client.close()
}
```

- Every call suspends until the whole response arrived: separate responses are awaited, Block1 and
  Block2 are done for you, a 4.01 with Echo is repeated with that Echo (under message security, only a
  verified one).
- Error codes come back as responses (`response.isSuccess`); everything else that goes wrong throws
  `CoapException` with a `reason` ([Errors](#errors)). Cancelling the coroutine cancels the exchange.
- `observe` returns a cold `Flow`: it registers when collected and deregisters (GET with Observe 1)
  when the collector stops. Collectors of the same resource share one registration.
- `CoapClientConfig { ... }` sets the request timeout, a `preferredBlockSize`, which turns on early negotiation
  (RFC 7959 §2.4), a size limit for downloads (`maxResponseBodySize`) and the `EndpointResolver`:
  `EndpointResolver.Default` takes IP literals for every scheme and `localhost`, and leaves host names of
  `coap+tcp` and `coap+ws` to the connector; a `coap://` host name needs a resolver of your own. A resolver
  throws `CoapException` with `UNRESOLVED_HOST` when a lookup fails at runtime, and
  `IllegalArgumentException` only for a host it cannot resolve by design. A lookup that times out in its own
  `withTimeout` is reported as `UNRESOLVED_HOST` too, so a `CancellationException` from a call always means
  that the calling coroutine was cancelled.
- `discover` reads the server's `/.well-known/core`; a malformed document is `BAD_RESPONSE`, saying where it
  broke. The link targets it returns are the server's text, so parse an absolute one with
  `CoapUri.parseOrNull`, which returns null instead of throwing.
- A `coaps://` URI fails with `TRANSPORT_UNAVAILABLE` before its host is resolved: kcoap has no DTLS yet, and
  nothing goes out in plaintext (RFC 7252 §6.2). Protect `coap://` requests with OSCORE instead.
- A `coaps+tcp://` or `coaps+ws://` URI needs TLS (RFC 8323 §9): it fails the same way unless a connector or
  listener of the endpoint runs TLS for its scheme, and when the resolver maps it to a peer not reached over TLS.

### Errors

Every call of `CoapClient`, `CoapEndpoint` and `CoapServer` fails at runtime with `CoapException` only, its
`cause` the lower layer's exception. `IllegalArgumentException` and `IllegalStateException` mean a mistake in
the calling code: a URI string that is not a CoAP URI, `observe` with a method other than GET or FETCH, a
host name given to `EndpointResolver.Default`.

| `reason` | What happened | What to do |
|---|---|---|
| `TRANSMISSION_TIMEOUT`, `RESPONSE_TIMEOUT`, `RESET` | The peer did not acknowledge, did not answer in time, or refused | Retry later; check the address |
| `SESSION_ENDED`, `CONNECTION_CLOSED` | The DTLS session or the connection ended | The next request opens a new one |
| `BAD_RESPONSE`, `BLOCKWISE_FAILED` | The response broke the protocol, or a body could not be assembled | Report it; retrying rarely helps |
| `MESSAGE_TOO_LARGE`, `IDENTIFIERS_EXHAUSTED` | A size limit, or a bound on exchanges or Message IDs (see [Limits](#limits-under-load)), was reached | Smaller bodies, fewer requests in flight |
| `SECURITY` | Message security could not protect the request; nothing was sent | Fix the context or install a protection (run EDHOC) |
| `UNVERIFIED_RESPONSE` | Only an error message security could not verify came back | Re-establish the context (run EDHOC); the request may have run |
| `UNRESOLVED_HOST` | The resolver could not resolve the host | Retry once the network is back |
| `TRANSPORT_UNAVAILABLE` | The endpoint has no transport for the URI's scheme; the scheme is `coaps`, which needs DTLS; or it is `coaps+tcp` or `coaps+ws` without a transport that runs TLS for it, or resolved to a peer not reached over TLS | Give the endpoint that transport; use OSCORE over `coap` until DTLS lands |
| `TRANSPORT_FAILED`, `CLOSED` | The endpoint's transport failed, or the endpoint was closed | Create a new endpoint |

A call after `close()` fails with `CLOSED` at once, before its host is looked up, and so does one that
races `close()`, a ping on any transport included. `Reason` may gain constants in any minor release, DTLS
for one, so a `when` over it keeps an `else` branch for the reasons it does not know.

`CoapException` extends `KcoapException`, like the exceptions of OSCORE, EDHOC and CBOR, so code that runs EDHOC
and then requests can catch `KcoapException` once. From Swift, the requests, `ping` and `discover` throw an
`NSError` whose `userInfo["KotlinException"]` is the `CoapException`, and `CoapUri.parse` throws one for a
malformed URI; a mistake of the caller ends the process, as a failed precondition does in Swift, so parse a URI
that is typed in before passing it.

## Client and server on one port

```kotlin
suspend fun peer(scope: CoroutineScope): Pair<CoapClient, CoapServer> {
    val endpoint = CoapEndpoint(KtorUdpTransport.bind("127.0.0.1", 5684), scope)
    val server = CoapServer(endpoint) { resource("status") { get { CoapResponse.content("ok") } } }
    return CoapClient(endpoint) to server
}
```

## Tuning the endpoint

```kotlin
val config = CoapEndpointConfig {
    parameters = TransmissionParameters(ackTimeout = 3.seconds, maxRetransmit = 6)
    separateResponseDelay = 500.milliseconds
    interceptors = listOf(object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage) = message.also { println("-> $to $it") }
        override fun onReceive(from: Endpoint, message: CoapMessage) = message.also { println("<- $from $it") }
    })
    eventListener = EndpointEventListener { event -> if (event is EndpointEvent.Retransmitted) println(event) }
}
```

`CoapEndpointConfig` and `CoapClientConfig` are built this way: the block sets options on a `Builder`, and
every option it leaves out keeps its default. `CoapEndpointConfig.Default` and `CoapClientConfig.Default` have
every default, `toBuilder()` starts a changed copy of a configuration, and `CoapEndpointConfig.Builder()` builds
one step by step, which is also how Swift sets the options it needs, since Swift does not see Kotlin's default
arguments. Two Kotlin rules apply inside the block: a listener or resolver names its interface
(`EndpointEventListener { ... }`, `EndpointResolver { ... }`), because a lambda becomes a `fun interface` only
as a function argument; and a local variable named like an option hides the option, so write
`CoapClientConfig { this.security = security }`. Values are checked when the configuration is built, which
throws `IllegalArgumentException` for one out of range.

`TransmissionParameters` holds RFC 7252 §4.8's ACK_TIMEOUT, ACK_RANDOM_FACTOR, MAX_RETRANSMIT, NSTART,
PROBING_RATE and MAX_LATENCY and derives EXCHANGE_LIFETIME and the other §4.8.2 values. Interceptors see
every message at the wire, after OSCORE protection; `toString()` of a message never shows its payload.
The event listener gets retransmissions, timeouts, Resets, duplicates, rejected and malformed messages,
bounds reached and observer changes; `EndpointEvent.ObserverRemoved` says why an observer went with a
`Reason` (`DEREGISTERED`, `RESET`, `NOT_ACKNOWLEDGED`, `RESOURCE_REMOVED`, `ERROR_RESPONSE`, `SESSION_ENDED`,
`FAILED`) to count by, and a `detail` to log. New events and reasons may come before 1.0, so keep an `else`
branch in a `when` over them.

## Limits under load

A UDP source address proves nothing (RFC 7252 §11.4), so a server must survive a flood of requests from
forged addresses without running out of memory and without amplifying it towards the addresses they name
(§11.3). Every structure such a flood fills is bounded in total and per peer, with defaults sized for a
server; a constrained device sets smaller ones:

```kotlin
val limits = CoapEndpointConfig {
    maxDeduplicationEntries = 5_000
    maxDeduplicationEntriesPerPeer = 500
    maxReservedMessageIds = 20_000
    maxExchanges = 1_024
    eventListener = EndpointEventListener { event ->
        if (event is EndpointEvent.LimitReached) println("refused for ${event.peer}: ${event.limit}")
    }
}
```

| Bound (`CoapEndpointConfig`) | Default | At the bound |
|---|---|---|
| `maxDeduplicationEntries`, `maxDeduplicationEntriesPerPeer` | 50 000, 10 000 | The oldest message RFC 7252 §4.5 lets the endpoint process again makes room: an answered GET, PUT, DELETE, FETCH or iPATCH (not one under OSCORE, whose replay window would refuse it the second time, RFC 8613 §7.4), a message answered with a Reset, a request refused or dropped before anything acted on it and before message security verified it. Otherwise the new message is dropped without an answer. POSTs take three quarters at most, so a POST flood never keeps out a GET, nor the responses to the endpoint's own requests |
| `maxReservedMessageIds`, `maxReservedMessageIdsPerPeer` | 262 144, 65 536 | A request or separate response that needs a new Message ID is refused, since §4.4 forbids reusing one within EXCHANGE_LIFETIME, and a notification goes later with the latest state, the observer kept. Responses to Non-confirmable requests, which a flood from forged addresses draws, are refused already at three quarters, so the rest stays free for what the endpoint must send. Piggybacked responses need none |
| `maxExchanges`, `maxExchangesPerPeer` | 16 384, 256 | In total, the oldest Non-confirmable message kept only for a Reset is forgotten, then the oldest Confirmable one still unacknowledged after a retransmission is given up, as a spoofed source never acknowledges; otherwise a request fails with `IDENTIFIERS_EXHAUSTED`, a separate response is not sent, and a notification goes later with the latest state |
| `maxConcurrentRequests` | 256 | A Confirmable request gets a piggybacked 5.03 with Max-Age 2, a Non-confirmable one is dropped |

The observer bounds are in `CoapServerConfig` (`maxObservers`, `maxObserversPerPeer`,
`maxObserversPerResource`: a registration beyond them is served without Observe, RFC 7641 §4.1), the
connection bounds in `ReliableTransportConfig`. What a bound refuses is reported as
`EndpointEvent.LimitReached`, whose `limit` names the bound, so a metrics adapter can count each.

A response much larger than its request, sent to a forged address, still amplifies an attack on that address
(RFC 9175 §2.6). `CoapServerConfig.amplification` chooses which responses wait until the client has shown, by
returning an Echo value, that it receives at its address: by default only Q-Block2 bursts. A server that faces
the Internet should set `AmplificationPolicy.ALL_RESPONSES`, which holds back every response larger than three
times its request; it is not the default because a client without RFC 9175 cannot answer the 4.01 with Echo.

**Too Many Requests** (RFC 8516). A server that wants a client which asks too often to wait answers with
4.29 and the wait as Max-Age; a client that gets one waits it out before it asks again:

```kotlin
fun ResourceBuilder.readings(limiter: Limiter) = resource("reading") {
    get {
        if (limiter.tooOften(peer)) CoapResponse.tooManyRequests(30.seconds, "one reading every 30 s")
        else CoapResponse.content("21.5")
    }
}

suspend fun readPolitely(client: CoapClient, uri: String): CoapResponse =
    RetryAfter(maxWait = 1.minutes).request(client, CoapRequest(CoapCode.GET, CoapUri.parse(uri)))
```

`tooManyRequests` rounds the wait up to whole seconds. `RetryAfter` sends the request again after each 4.29
or 5.03 once its `retryAfter` (the Max-Age, 60 seconds without one) has passed, `maxAttempts` times in all
(3), and returns a refusal whose wait is longer than `maxWait` at once rather than ask too early. Cancelling
the caller ends the wait. Without message security a 4.29 can be forged (RFC 8516 §5): `maxWait` bounds what
that costs. RFC 8516 §3 keeps 4.29 for the clients that ask too often, and 5.03 for an overload of all, which
is what `maxConcurrentRequests` answers. kcoap has no rate limiter of its own: per-client state can add to
the load it is meant to shed, so the policy (`Limiter` above) is the application's.

## Over TCP and WebSockets

CoAP over reliable transports (RFC 8323) uses the same client and server: the URI scheme picks the
transport. A server takes listeners, a client takes connectors; both can also share a `CoapEndpoint`
with a UDP transport: `CoapEndpoint(scope, config, transport = udp, connectors = ..., listeners = ...)`.
Block-wise, Observe, Q-Block, Echo, access policies and OSCORE run the same code on every transport.

```kotlin
suspend fun reliableServer(scope: CoroutineScope, tcpPort: Int, webSocketPort: Int): CoapServer {
    val listeners = listOf(
        KtorTcpListener.bind("127.0.0.1", tcpPort), // coap+tcp
        KtorWebSocketListener.start(CIO, "127.0.0.1", webSocketPort), // coap+ws, at /.well-known/coap
    )
    return CoapServer(listeners, scope) {
        resource("hello") { get { CoapResponse.content("Hello over ${peer::class.simpleName}") } }
    }
}

suspend fun reliableClient(scope: CoroutineScope, tcpPort: Int, webSocketPort: Int) {
    val client = CoapClient(listOf(KtorTcpConnector(), KtorWebSocketConnector()), scope)
    println(client.get("coap+tcp://127.0.0.1:$tcpPort/hello").payloadAsText())
    println(client.get("coap+ws://127.0.0.1:$webSocketPort/hello").payloadAsText())
    println(client.ping("coap+tcp://127.0.0.1:$tcpPort"))
    client.close()
}
```

- Modules: `kcoap-transport-ktor` (TCP on ktor-network), `kcoap-transport-ktor-websocket` (the WebSocket
  client on the ktor client, any engine, browsers included) and `kcoap-transport-ktor-websocket-server`
  (a standalone server on the ktor engine you pass, such as `io.ktor.server.cio.CIO` from
  `io.ktor:ktor-server-cio`, or `routing { coapOverWebSockets(listener) }` inside your own ktor
  application). The server module brings no engine of its own.
- The client opens a connection when a request needs one, sends its CSM, waits for the server's
  (RFC 8323 §3.3) and reuses the connection for every request to that peer. An idle connection it opened
  is released (§5.5). If a connection closes, its exchanges fail with
  `CoapException.Reason.CONNECTION_CLOSED` and its observations end; reconnecting is the next request.
- Messages are as large as the peer's Max-Message-Size allows, so bodies that fit need no blocks. Larger
  ones go block-wise, in BERT blocks (§6) when both CSMs announce BERT.
- Observe works as over UDP. Notifications carry no message type, their Observe values are ignored in
  favour of arrival order (§7.1), and cancelling always deregisters (§7.4).
- `ping` sends a CoAP Ping and waits for the Pong (§5.4); `endpoint.ping(peer, custody = true)` asks for
  Custody (§5.4.1).
- A request or ping to a scheme the endpoint has no transport for, such as `coap://` on a client made from
  connectors only, fails at once with `CoapException.Reason.TRANSPORT_UNAVAILABLE`, naming the scheme.
- `coaps+tcp` goes over `KtorTlsConnector` (kcoap-transport-ktor, on the JVM), which checks the server's
  certificate against the platform trust store or given anchors and takes a pinning hook; `coaps+ws` over `wss://`
  on the engine's TLS, where it authenticates the server: `KtorWebSocketConnector()` serves it on Apple and the
  web, not on the JVM and Android, whose CIO engine does not check host names fully, so there pass an `HttpClient`
  of your own. Which runtime has which TLS, and how servers terminate it, is in
  [platforms.md](platforms.md#tls-support-per-runtime).

```kotlin
val reliableConfig = CoapEndpointConfig {
    reliable = ReliableTransportConfig(
        maxMessageSize = 64 * 1024 + 512, // announced in the CSM; BERT blocks of up to 64 KiB
        idleTimeout = 2.minutes, // release connections this side opened after 2 idle minutes
        keepAliveInterval = 30.seconds, // Ping a quiet connection; abort it if nothing answers
    )
}
```

`EndpointEvent`s report connections opened and closed, CSMs, Releases and Aborts. On virtual time,
`kcoap-testkit`'s `InMemoryConnections` stands in for sockets:

```kotlin
@Test
fun overConnections() = runTest {
    val connections = InMemoryConnections()
    val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
    CoapServer(listOf(connections.listen(5683)), backgroundScope, endpointConfig = config) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }
    val client = CoapClient(listOf(connections.connector), backgroundScope, endpointConfig = config)
    assertEquals("Hello", client.get("coap+tcp://127.0.0.1:5683/hello").payloadAsText())
}
```

## Q-Block (RFC 9177)

Q-Block moves large bodies in bursts of blocks instead of one round trip per block, and recovers lost
blocks without stopping the burst. It is **off by default on both sides**; with it off, nothing changes:
bodies use Block1 and Block2 (RFC 7959). Turn it on in the server and in the client:

```kotlin
suspend fun qBlockServer(scope: CoroutineScope, port: Int): CoapServer {
    val big = ByteArray(20_000) { (it % 251).toByte() }
    var stored = ByteArray(0)
    val config = CoapServerConfig(qBlock = QBlockServerConfig(isEnabled = true)) // off by default
    return CoapServer(KtorUdpTransport.bind("127.0.0.1", port), scope, config) {
        resource("big") { get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) } }
        resource("upload") {
            put {
                stored = payload
                CoapResponse.changed()
            }
            get { CoapResponse.content(stored, ContentFormat.OCTET_STREAM) }
        }
    }
}

suspend fun qBlockClient(scope: CoroutineScope, port: Int) {
    val config = CoapClientConfig { qBlock = QBlockClientConfig(mode = QBlockMode.PROBE) } // OFF by default
    val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, config)
    val server = "coap://127.0.0.1:$port"
    // Non-confirmable requests get Non-confirmable bursts; Confirmable ones go block by block.
    val body = client.get("$server/big") { nonConfirmable() }.payload
    client.put("$server/upload", body) { nonConfirmable() }
    println("${body.size} bytes down and up")
    client.close()
}
```

- **Finding out.** `QBlockMode.PROBE` asks each server once, with the Confirmable
  `GET /.well-known/core` carrying Q-Block2 that libcoap sends too, and the endpoint remembers the answer
  for `capabilityLifetime` (1 hour) for all its clients. A server that does not take Q-Block gets RFC 7959.
  `ASSUME_SUPPORTED` skips the probe for servers you know; a rejection still falls back to RFC 7959.
- **Message type.** A body follows its request: `nonConfirmable()` gives the bursts of MAX_PAYLOADS
  (10) blocks of §7.2, with 2.31, Continue and lists of missing blocks; the default Confirmable request
  sends one block per ACK. A Non-confirmable body holds one NSTART slot towards the peer while it lasts.
- **Observe.** Where both sides have Q-Block on, `observe` registers with Q-Block2 and every
  notification comes as a Q-Block2 body, emitted once it is whole.
- **TCP and WebSockets.** The client keeps Q-Block off there (`onReliableTransports = false`), since
  BERT does the same with fewer messages; the server takes it (`QBlockServerConfig.onReliableTransports`).
- **OSCORE.** Q-Block under message security is opt-in on both sides: `withMessageSecurity = true` in
  `QBlockClientConfig` and in `QBlockServerConfig`, with kcoap-oscore on both. Otherwise protected bodies
  use Inner Block1 and Block2. libcoap 4.3.5 protects only one response per request, so against it keep
  the defaults.
- **Amplification.** RFC 9177 §11 does not recommend Q-Block without security, and the server warns
  once at start. Under the default `AmplificationPolicy.Q_BLOCK_BURSTS` a client that nothing
  authenticated gets a 4.01 with Echo before its first burst (RFC 9175 §2.4); kcoap's client, and libcoap's,
  repeat the request with the Echo value.
- **Parameters.** `TransmissionParameters` holds MAX_PAYLOADS, NON_TIMEOUT, NON_RECEIVE_TIMEOUT and
  NON_MAX_RETRANSMIT (Table 3); MAX_PAYLOADS must be the same on both peers. Each body gets an ETag of its
  own, which replaces the resource's ETag on Q-Block2 bodies.
- **Events.** `EndpointEvent.QBlockFallback` reports a server found not to take Q-Block,
  `QBlockRecovery` blocks asked for again, and `PartialBodyDropped` a body given up.

## Message security

`CoapServerConfig(security = …)` and `CoapClientConfig { security = … }` take the SPI from
`dev.kcoap.coap.security` that `kcoap-oscore` implements. The stack calls it between the exchange layer
and messaging, as RFC 8613 Figure 1 does: resources see decrypted requests, every block and every
notification is protected on its own, and failures are answered unprotected with Max-Age 0.

Configuring it fails closed ([ADR 0008](adr/0008-fail-closed-message-security.md)):

- **Who may use a resource** is its `AccessPolicy`: `access = AccessPolicy.Public` in the DSL (or override
  `CoapResource.access`), `MessageSecurityRequired`, `Authenticated` (message security, or DTLS/TLS for the
  hop), or a lambda on the `AccessRequest`, which carries the method, the path, the resource, the peer, the
  `securityIdentity` and the channel's `peerIdentity`. A policy covers the resource and everything below it
  that sets none; `CoapServerConfig.defaultAccess` covers the rest, and is `MessageSecurityRequired` when
  `security` is set, `Public` otherwise. `/.well-known/core` and the EDHOC resource are `Public`.
- **The policy decides first.** A refused request gets 4.01 (4.03 when message security authenticated it and
  the policy still says no) before any handler runs, any Block1 or Q-Block1 body is kept, any observation is
  registered or cancelled, or any burst goes out. A path that does not exist is refused like its nearest
  existing ancestor, so protected names do not leak through 4.04. `/.well-known/core` lists only what the
  caller may use, and marks OSCORE-only resources with `osc` (RFC 8613 §9).
- **Who sent it.** `securityIdentity` is the identity of the security context: an `OscoreIdentity` (Recipient
  ID and ID Context), or, for a context EDHOC established, an `EdhocIdentity` with the peer's authenticated
  credential (kcoap-edhoc-oscore). An Observe registration belongs to the identity that made it, so one client
  can neither replace nor cancel another's, nor register on its endpoint and token: that is answered as a
  plain GET.
- **What the client acts on.** Only responses its security layer verified. OSCORE sends its errors without
  protection (RFC 8613 §8.2), and anyone who sees the token can send one too, so such a response neither
  has the request repeated with its Echo value nor steers a block-wise or Q-Block body nor ends an
  observation. A request answered only that way fails with `CoapException` and
  `Reason.UNVERIFIED_RESPONSE`; `exception.response` is the error as it came. A server that lost its
  context answers like this: run EDHOC again rather than repeating a request that may already have run.
- **No silent plaintext.** `OscoreClient` fails a request its resolver finds no context for, and
  kcoap-edhoc-oscore's `PeerSecurity` one to a server without an installed protection, before anything is
  sent: the client throws `CoapException` with `Reason.SECURITY`, whose `cause` is the security layer's
  `MessageSecurityException`; `allowUnprotected = true` on either sends such
  requests in plain CoAP on purpose. Discovering the EDHOC resource before the handshake therefore takes a
  plain client (or `/.well-known/core` in `PeerSecurity`'s `unprotectedPaths`); a Q-Block probe the security
  layer will not send yet is skipped for that request, so EDHOC works on a client with `QBlockMode.PROBE`.
  After the handshake, `security.install(client, serverUri, protection)` installs the protection for the
  endpoint the client's own resolver finds for that URI, the one its requests go to.

## Testing without a network

```kotlin
@Test
fun greets() = runTest {
    val network = InMemoryNetwork(backgroundScope, Random(1))
    network.conditions = LinkConditions(lossRate = 0.3, duplicationRate = 0.1)
    val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
    val serverTransport = network.attach()
    CoapServer(serverTransport, backgroundScope, endpointConfig = config) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }
    val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
    val address = serverTransport.localEndpoint
    assertEquals("Hello", client.get("coap://${address.host}:${address.port}/hello").payloadAsText())
}
```

With `testScheduler.timeSource` and `backgroundScope`, retransmissions, lifetimes and network latency
all run on virtual time: a test of a 93-second give-up takes milliseconds.

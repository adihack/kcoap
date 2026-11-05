# Guide: CoAP clients and servers

How to serve and request CoAP resources with kcoap over UDP, TCP and WebSockets. Every example here is
compiled and run (on free ports instead of 5683) by
`kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/GuideExamplesTest.kt`.

Modules: `kcoap-coap` (the stack), `kcoap-transport-ktor` (UDP and TCP sockets),
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
- A handler gets a `RequestContext`: the (verified, reassembled) `request`, the `peer` endpoint, its
  DTLS `peerIdentity`, the OSCORE `securityIdentity`, and `acknowledge()` to promise a separate response
  early.
- Large responses and request bodies go block-wise automatically, in blocks that fit the transport's
  datagram size. `/.well-known/core` lists every resource with handlers, with `obs` for observable ones.
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
  Block2 are done for you, a 4.01 with Echo is repeated with that Echo.
- Error codes come back as responses (`response.isSuccess`); no answer at all throws `CoapException`
  with a `reason` such as `TRANSMISSION_TIMEOUT` or `RESET`. Cancelling the coroutine cancels the
  exchange.
- `observe` returns a cold `Flow`: it registers when collected and deregisters (GET with Observe 1)
  when the collector stops. Collectors of the same resource share one registration.
- `CoapClientConfig` sets the request timeout, a block size for early negotiation, a size limit for
  downloads and the `EndpointResolver` (IP literals and `localhost` by default).

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
val config = CoapEndpointConfig(
    parameters = TransmissionParameters(ackTimeout = 3.seconds, maxRetransmit = 6),
    separateResponseDelay = 500.milliseconds,
    interceptors = listOf(object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage) = message.also { println("-> $to $it") }
        override fun onReceive(from: Endpoint, message: CoapMessage) = message.also { println("<- $from $it") }
    }),
    eventListener = { event -> if (event is EndpointEvent.Retransmitted) println(event) },
)
```

`TransmissionParameters` holds RFC 7252 §4.8's ACK_TIMEOUT, ACK_RANDOM_FACTOR, MAX_RETRANSMIT, NSTART,
PROBING_RATE and MAX_LATENCY and derives EXCHANGE_LIFETIME and the other §4.8.2 values. Interceptors see
every message at the wire, after OSCORE protection; `toString()` of a message never shows its payload.

## Over TCP and WebSockets

CoAP over reliable transports (RFC 8323) uses the same client and server: the URI scheme picks the
transport. A server takes listeners, a client takes connectors; both can also share a `CoapEndpoint`
with a UDP transport.

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
- `coaps+ws` works with `wss://` on the engine's TLS. `coaps+tcp` needs a TLS connector, which is not
  built yet.

```kotlin
val reliableConfig = CoapEndpointConfig(
    reliable = ReliableTransportConfig(
        maxMessageSize = 64 * 1024 + 512, // announced in the CSM; BERT blocks of up to 64 KiB
        idleTimeout = 2.minutes, // release connections this side opened after 2 idle minutes
        keepAliveInterval = 30.seconds, // Ping a quiet connection; abort it if nothing answers
    ),
)
```

`EndpointEvent`s report connections opened and closed, CSMs, Releases and Aborts. On virtual time,
`kcoap-testkit`'s `InMemoryConnections` stands in for sockets:

```kotlin
@Test
fun overConnections() = runTest {
    val connections = InMemoryConnections()
    val config = CoapEndpointConfig(timeSource = testScheduler.timeSource)
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
    val qBlock = QBlockClientConfig(mode = QBlockMode.PROBE) // OFF by default
    val client = CoapClient(KtorUdpTransport.bind("127.0.0.1"), scope, CoapClientConfig(qBlock = qBlock))
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

`CoapServerConfig(security = …)` and `CoapClientConfig(security = …)` take the SPI from
`dev.kcoap.coap.security` that `kcoap-oscore` implements. The stack calls it between the exchange layer
and messaging, as RFC 8613 Figure 1 does: resources see decrypted requests, every block and every
notification is protected on its own, and failures are answered unprotected with Max-Age 0.

## Testing without a network

```kotlin
@Test
fun greets() = runTest {
    val network = InMemoryNetwork(backgroundScope, Random(1))
    network.conditions = LinkConditions(lossRate = 0.3, duplicationRate = 0.1)
    val config = CoapEndpointConfig(timeSource = testScheduler.timeSource)
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

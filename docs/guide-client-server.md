# Guide: CoAP clients and servers

How to serve and request CoAP resources with kcoap over UDP. Every example here is compiled and run (on
free ports instead of 5683) by `kcoap-coap/src/jvmTest/kotlin/dev/kcoap/coap/GuideExamplesTest.kt`.

Modules: `kcoap-coap` (the stack), `kcoap-transport-ktor` (UDP sockets), and for tests
`kcoap-testkit` (a simulated network).

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

# kcoap-testkit

An in-memory datagram network and in-memory connections for tests: of kcoap itself, and of
applications built on it.

- `InMemoryNetwork`: attaches `InMemoryTransport`s (a `DatagramTransport` each) at UDP-style
  addresses and delivers datagrams between them in memory.
- `LinkConditions`: loss, duplication, latency and jitter (and so reordering). Decisions come from
  the network's seeded `Random`, so a failing run can be replayed.
- `InMemoryNetwork.filter` drops chosen datagrams deterministically ("lose the first ACK"), and
  `InMemoryNetwork.tap` sees every datagram with its `DatagramFate`.
- `InMemoryNetwork.spoof` sends a datagram from a forged source address with nothing attached there, so
  whatever answers it is unreachable, as with spoofed UDP; flood tests send from thousands of addresses so.
- `InMemoryTransport.inject` hands any `DatagramEvent` to the receiver, for example
  `SessionEnded` to simulate a DTLS layer.
- `RebindingTransport`: a client behind a NAT that rebinds. It sends from one attached transport until
  a chosen datagram and from a second one after that, so the peer sees the source address change in the
  middle of a conversation; it receives on both, or only on the new one when the old binding expired.
- `InMemoryConnections`: listeners and a `Connector` for `coap+tcp`, `coaps+tcp`, `coap+ws` and
  `coaps+ws` (RFC 8323); a client reaches a listener at every endpoint equal to its address. Byte
  streams deliver writes in pieces of `chunkSize` bytes, so stream reassembly is exercised; message
  streams keep frames whole. `InMemoryConnection.inject` hands raw
  bytes to one side, `reset` breaks a connection, `tap` sees every write. TLS is not simulated; a
  listener reports the identities it is given.
- `InMemorySessions`: a stand-in for a DTLS layer on an `InMemoryTransport`. Every peer appears as an
  `Endpoint.Secure`, one session per address with the identity it is given, nothing is encrypted, and
  `endAll` ends every session as a DTLS layer reports it (`DatagramEvent.SessionEnded`, RFC 7252
  §9.1.1).
- `InMemoryLink`: one test, run over both ways a CoAP endpoint reaches a peer. `InMemoryLink.Datagrams`
  (an `InMemoryNetwork` through `InMemorySessions`) and `InMemoryLink.Connections` (`InMemoryConnections`)
  give a server `Side` and client `Side`s with the arguments of the endpoint's constructor, the server's
  `uri`, and `cut`, which ends every session or connection between them at once. kcoap-coap's
  `ExchangeConformanceTest` and kcoap-oscore's `OscoreOverLinksTest` run on it.
- `Stress`: concurrency stress tests on real threads. `Stress.run` repeats a body a bounded number of
  times on `Dispatchers.Default`, several iterations at once, within a real-time timeout, and gives each
  iteration a `background` scope that ends with it. An iteration that fails, or is cancelled from inside
  (a `withTimeout` in it that expires), fails the run. `Stress.race` starts several actions at the same
  moment and keeps each outcome. On the JVM and Kotlin/Native that is a thread pool; on JS and Wasm the
  event loop, so the same common test still interleaves at suspension points.

Delays run on coroutine time: under `runTest`, pass `backgroundScope` to the network and
`testScheduler.timeSource` to the CoAP endpoint, and retransmissions, lifetimes and latency all run
on virtual time.

```kotlin
@Test
fun survivesALossyNetwork() = runTest {
    val network = InMemoryNetwork(backgroundScope, Random(42))
    network.conditions = LinkConditions(lossRate = 0.2, duplicationRate = 0.1, jitter = 50.milliseconds)
    val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
    val server = CoapServer(network.attach(), backgroundScope, endpointConfig = config) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }
    val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
    assertEquals("Hello", client.get("coap://127.0.0.1:50000/hello").payloadAsText())
}
```

## Why a separate module

`kcoap-transport` holds the transport interfaces and depends only on `kcoap-common`; the network needs
kotlinx-coroutines and is only useful in tests, so applications depend on it with
`testImplementation` and do not ship it. It depends on `kcoap-transport` only, so `kcoap-coap`'s own
tests can use it without a dependency cycle.

Stress tests leave virtual time: endpoints under `Stress.run` use the real clock
(`TimeSource.Monotonic`, the default), because their dispatcher is a real one.

```kotlin
@Test
fun closeRacesRequests() = runTest {
    Stress.run(iterations = 50) {
        val client = CoapClient(network.attach(), background)
        Stress.race({ client.get(uri) }, { client.close() })
    }
}
```

A test that runs once per link takes an `InMemoryLink` and builds its endpoints from the sides:

```kotlin
abstract class MyConformanceTest {
    abstract fun link(network: InMemoryNetwork, connections: InMemoryConnections): InMemoryLink

    @Test
    fun getsTheResource() = runTest {
        val link = link(InMemoryNetwork(backgroundScope), InMemoryConnections())
        val config = CoapEndpointConfig { timeSource = testScheduler.timeSource }
        val server = link.server()
        CoapServer(CoapEndpoint(backgroundScope, config, server.transport, server.connectors, server.listeners)) {
            resource("hello") { get { CoapResponse.content("Hello") } }
        }
        val client = link.client()
        val resolver = EndpointResolver { uri -> client.peer(EndpointResolver.Default.resolve(uri)) }
        val coap = CoapClient(CoapEndpoint(backgroundScope, config, client.transport, client.connectors), CoapClientConfig { this.resolver = resolver })
        assertEquals("Hello", coap.get("${link.uri}/hello").payloadAsText())
    }
}

class OverDatagrams : MyConformanceTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections) = InMemoryLink.Datagrams(network)
}

class OnConnections : MyConformanceTest() {
    override fun link(network: InMemoryNetwork, connections: InMemoryConnections) = InMemoryLink.Connections(connections)
}
```

Verified by `InMemoryNetworkTest`, `InMemoryConnectionsTest`, `InMemoryLinkTest`, `StressTest` and by every
end-to-end test of `kcoap-coap`.

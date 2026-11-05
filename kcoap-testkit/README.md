# kcoap-testkit

An in-memory datagram network and in-memory connections for tests: of kcoap itself, and of
applications built on it.

- `InMemoryNetwork`: attaches `InMemoryTransport`s (a `DatagramTransport` each) at UDP-style
  addresses and delivers datagrams between them in memory.
- `LinkConditions`: loss, duplication, latency and jitter (and so reordering). Decisions come from
  the network's seeded `Random`, so a failing run can be replayed.
- `InMemoryNetwork.filter` drops chosen datagrams deterministically ("lose the first ACK"), and
  `InMemoryNetwork.tap` sees every datagram with its `DatagramFate`.
- `InMemoryTransport.inject` hands any `DatagramEvent` to the receiver, for example
  `SessionEnded` to simulate a DTLS layer.
- `InMemoryConnections`: listeners and a `Connector` for `coap+tcp`, `coaps+tcp`, `coap+ws` and
  `coaps+ws` (RFC 8323). Byte streams deliver writes in pieces of `chunkSize` bytes, so stream
  reassembly is exercised; message streams keep frames whole. `InMemoryConnection.inject` hands raw
  bytes to one side, `reset` breaks a connection, `tap` sees every write. TLS is not simulated; a
  listener reports the identities it is given.
- `Stress`: concurrency stress tests on real threads. `Stress.run` repeats a body a bounded number of
  times on `Dispatchers.Default`, several iterations at once, within a real-time timeout, and gives each
  iteration a `background` scope that ends with it; `Stress.race` starts several actions at the same
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
    val config = CoapEndpointConfig(timeSource = testScheduler.timeSource)
    val server = CoapServer(network.attach(), backgroundScope, endpointConfig = config) {
        resource("hello") { get { CoapResponse.content("Hello") } }
    }
    val client = CoapClient(network.attach(), backgroundScope, endpointConfig = config)
    assertEquals("Hello", client.get("coap://127.0.0.1:50000/hello").payloadAsText())
}
```

## Why a separate module

`kcoap-transport` holds the transport interfaces and has no dependencies; the network needs
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

Verified by `InMemoryNetworkTest`, `InMemoryConnectionsTest`, `StressTest` and by every end-to-end test
of `kcoap-coap`.

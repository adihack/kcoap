package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.server.ObservableResource
import dev.kcoap.coap.server.QBlockServerConfig
import dev.kcoap.coap.server.RequestContext
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LoggerFactory
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.testkit.Stress
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Q-Block bodies (RFC 9177) under real concurrency with the testkit's [Stress]: on the JVM and Kotlin/Native
 * the bodies, their timers and the server's handlers race on a thread pool, on JS and Wasm they interleave at
 * suspension points. Each iteration runs kcoap's client against its server on an in-memory network of its
 * own, on the real clock, and checks what a race would break: every body arrives whole or fails the documented
 * way, notification bodies arrive whole and in order, and nothing is left registered.
 */
@OptIn(ExperimentalAtomicApi::class)
class QBlockStressTest {
    private val quiet = CoapEndpointConfig(loggerFactory = LoggerFactory(LogConfig.build { level = LogLevel.WARN }))
    private val qBlockServer = CoapServerConfig(amplification = AmplificationPolicy.OFF, qBlock = QBlockServerConfig(isEnabled = true))
    private val qBlockClient = CoapClientConfig(blockSize = 64, qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED))

    private fun body(size: Int, seed: Int): ByteArray = ByteArray(size) { (it * 31 + seed).toByte() }

    private fun uriOf(server: CoapServer, path: String): String =
        "coap://127.0.0.1:${(server.endpoint.transport as InMemoryTransport).localEndpoint.port}$path"

    /** A server with `/big` (3000 bytes, 47 blocks of 64) and `/store`, whose PUT keeps and POST turns around its body. */
    private suspend fun server(network: InMemoryNetwork, scope: CoroutineScope, stored: AtomicReference<List<ByteArray>>): CoapServer =
        CoapServer(network.attach(), scope, qBlockServer, quiet) {
            resource("big") { get { CoapResponse.content(body(3000, 1), ContentFormat.OCTET_STREAM) } }
            resource("store") {
                put {
                    while (true) {
                        val current = stored.load()
                        if (stored.compareAndSet(current, current + payload)) break
                    }
                    CoapResponse.changed()
                }
                post { CoapResponse(CoapCode.CHANGED, payload = payload.reversedArray()) }
            }
        }

    @Test
    fun concurrentBodiesBothWaysArriveWholeAndLeaveNothingBehind() = runTest(timeout = 3.minutes) {
        Stress.run(iterations = 20, timeout = 90.seconds) {
            val network = InMemoryNetwork()
            val stored = AtomicReference<List<ByteArray>>(emptyList())
            val server = server(network, background, stored)
            val client = CoapClient(network.attach(), background, qBlockClient, quiet)
            val upload = body(3000, iteration)

            val results = Stress.race<Any>(
                { client.get(uriOf(server, "/big")) { nonConfirmable() }.payload },
                { client.get(uriOf(server, "/big")).payload },
                { client.put(uriOf(server, "/store"), upload) { nonConfirmable() }.code },
                { client.put(uriOf(server, "/store"), upload).code },
                { client.post(uriOf(server, "/store"), upload) { nonConfirmable() }.payload },
            )

            val values = results.map { it.getOrElse { error -> fail("iteration $iteration: a body failed: $error") } }
            assertContentEquals(body(3000, 1), values[0] as ByteArray, "NON download")
            assertContentEquals(body(3000, 1), values[1] as ByteArray, "CON download")
            assertEquals(CoapCode.CHANGED, values[2])
            assertEquals(CoapCode.CHANGED, values[3])
            assertContentEquals(upload.reversedArray(), values[4] as ByteArray, "an upload answered with a Q-Block2 body")
            assertEquals(2, stored.load().size)
            assertTrue(stored.load().all { it.contentEquals(upload) }, "only whole bodies reach the handler")
            val state = server.qBlock.state()
            assertTrue(state.servedBodies > 0 && state.completedBodies > 0, "the bodies went in Q-Block: $state")
            withTimeoutOrNull(10.seconds) {
                while (client.endpoint.exchangeCount() > 0 || server.qBlock.state().partialBodies > 0) delay(5)
            } ?: fail("left behind: ${client.endpoint.exchangeCount()} exchanges, ${server.qBlock.state()}")
            client.close()
        }
    }

    @Test
    fun closeRacingBodiesEndsEachWholeOrClosed() = runTest(timeout = 3.minutes) {
        Stress.run(iterations = 30, timeout = 90.seconds) {
            val network = InMemoryNetwork()
            val server = server(network, background, AtomicReference(emptyList()))
            val client = CoapClient(network.attach(), background, qBlockClient, quiet)
            val bodies = List(4) { index ->
                suspend {
                    if (index % 2 == 0) {
                        client.get(uriOf(server, "/big")) { nonConfirmable() }.payload
                    } else {
                        client.post(uriOf(server, "/store"), body(3000, index)) { nonConfirmable() }.payload
                    }
                }
            }

            val results = Stress.race<Any>(*bodies.toTypedArray(), {
                delay(1)
                client.endpoint.close()
            })
            results.dropLast(1).forEachIndexed { index, result ->
                result.fold(
                    { payload ->
                        val expected = if (index % 2 == 0) body(3000, 1) else body(3000, index).reversedArray()
                        assertContentEquals(expected, payload as ByteArray, "a body that completed is whole")
                    },
                    { error -> assertEquals(CoapException.Reason.CLOSED, assertIs<CoapException>(error, "$error").reason) },
                )
            }
            assertEquals(0, client.endpoint.exchangeCount(), "no exchange registered after close()")
        }
    }

    /** A resource of 3000 bytes per version, notifying in Confirmable bodies, changed from many threads at once. */
    private class Changing : ObservableResource("obs") {
        val version = AtomicInt(0)

        override val notificationType: CoapMessageType get() = CoapMessageType.CON

        override suspend fun handleGet(context: RequestContext): CoapResponse =
            CoapResponse.content(bodyOf(version.load()), ContentFormat.OCTET_STREAM)

        fun change() {
            version.addAndFetch(1)
            notifyObservers()
        }

        companion object {
            fun bodyOf(version: Int): ByteArray = ByteArray(3000) { (it * 7 + version).toByte() }
        }
    }

    /**
     * Three coroutines change a resource 30 times while kcoap's client observes it with Q-Block: the bodies arrive
     * whole and in order, once each, up to the latest state. The changes start once the first notification
     * arrived, so the check never runs on an empty list, and the server's sends show that notification bodies went
     * in Q-Block2 (RFC 9177 §4.5), not in RFC 7959.
     */
    @Test
    fun notificationBodiesUnderConcurrentChangesArriveWholeAndInOrder() = runTest(timeout = 3.minutes) {
        Stress.run(iterations = 12, timeout = 120.seconds) {
            val network = InMemoryNetwork()
            val resource = Changing()
            val qBlockNotifications = AtomicInt(0)
            val recorder = object : MessageInterceptor {
                override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also {
                    if (it.options.qBlock2 != null && it.options.observe != null) qBlockNotifications.addAndFetch(1)
                }
            }
            val serverEndpoint = CoapEndpointConfig(loggerFactory = quiet.loggerFactory, interceptors = listOf(recorder))
            val server = CoapServer(network.attach(), background, qBlockServer, serverEndpoint) { add(resource) }
            val client = CoapClient(network.attach(), background, qBlockClient, quiet)
            val last = 30
            val changers = mutableListOf<Job>()
            fun startChangers() {
                repeat(3) {
                    changers += launch {
                        repeat(last / 3) {
                            resource.change()
                            delay(1)
                        }
                    }
                }
            }

            val versions = (0..last).associateBy { Changing.bodyOf(it).toList() }
            val received = withTimeoutOrNull(60.seconds) {
                client.observe(uriOf(server, "/obs"))
                    .onEach { if (changers.isEmpty()) startChangers() }
                    .takeWhile { versions[it.payload.toList()] != last }
                    .toList()
            } ?: fail("iteration $iteration: the latest state never arrived")
            changers.forEach { it.join() }
            val delivered = received.map { versions[it.payload.toList()] ?: fail("a notification that is no whole body") }
            assertEquals(0, delivered.firstOrNull(), "the registration's body came before any change")
            assertEquals(delivered.sorted(), delivered, "in order")
            assertEquals(delivered.distinct(), delivered, "one emission per body")
            assertEquals(last, resource.version.load())
            // The registration's body and at least the latest state's, 47 blocks each, every block with Observe.
            assertTrue(qBlockNotifications.load() >= 2 * 47, "notification bodies went in Q-Block2: ${qBlockNotifications.load()} blocks")
            withTimeoutOrNull(10.seconds) {
                while (resource.observerCount > 0 || client.endpoint.exchangeCount() > 0) delay(5)
            } ?: fail("left behind: ${resource.observerCount} observers, ${client.endpoint.exchangeCount()} exchanges")
            client.close()
        }
    }
}

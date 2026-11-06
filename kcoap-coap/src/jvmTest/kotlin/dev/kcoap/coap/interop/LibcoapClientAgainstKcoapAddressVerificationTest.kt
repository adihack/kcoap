package dev.kcoap.coap.interop

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.server.AmplificationPolicy
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEventListener
import dev.kcoap.coap.stack.MessageInterceptor
import dev.kcoap.transport.Endpoint
import dev.kcoap.transport.ktor.KtorUdpTransport
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * libcoap's `coap-client` against a kcoap server that verifies client addresses before large responses
 * (RFC 9175 §2.4 item 3, §2.6; [AmplificationPolicy.ALL_RESPONSES]) over real UDP. Every `docker run` sends
 * from a new source port, so each test starts from an address the server has not verified. Skipped when
 * docker or the `kcoap/libcoap` image is missing.
 */
@Tag("interop")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LibcoapClientAgainstKcoapAddressVerificationTest {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var server: CoapServer
    private lateinit var uri: String
    private val directory: File = Files.createTempDirectory("kcoap-interop").toFile()
    private val large: String = Random(9).let { random -> String(CharArray(3000) { 'a' + random.nextInt(26) }) }

    private val sent: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    private val received: MutableList<CoapMessage> = Collections.synchronizedList(mutableListOf())
    private val events: MutableList<EndpointEvent> = Collections.synchronizedList(mutableListOf())
    private val recorder = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message.also { sent += it }

        override fun onReceive(from: Endpoint, message: CoapMessage): CoapMessage = message.also { received += it }
    }

    @BeforeAll
    fun startServer() {
        assumeTrue(Libcoap.isAvailable, "docker or ${Libcoap.IMAGE} not available")
        runBlocking {
            server = CoapServer(
                KtorUdpTransport.bind("127.0.0.1"), scope,
                config = CoapServerConfig(amplification = AmplificationPolicy.ALL_RESPONSES),
                endpointConfig = CoapEndpointConfig {
                    interceptors = listOf(recorder)
                    eventListener = EndpointEventListener { events += it }
                },
            ) {
                resource("large") { get { CoapResponse.content(large) } }
            }
            uri = "coap://127.0.0.1:${(server.endpoint.transport as KtorUdpTransport).localEndpoint.port}"
        }
    }

    @AfterAll
    fun stopServer() {
        if (::server.isInitialized) runBlocking { server.close() }
        scope.cancel()
        directory.deleteRecursively()
    }

    @BeforeEach
    fun clear() {
        sent.clear()
        received.clear()
        events.clear()
    }

    /** The 4.01 the server sent, and the request that returned its Echo value. */
    private fun echoExchange(): Pair<CoapMessage, CoapMessage> {
        val challenge = sent.single { it.code == CoapCode.UNAUTHORIZED }
        val echo = assertNotNull(challenge.options.echo, "$challenge")
        val repeat = received.first { it.isRequest && it.options.echo?.contentEquals(echo) == true }
        return challenge to repeat
    }

    @Test
    fun completesARequestAfterTheEchoExchange() {
        val output = File(directory, "large.txt")
        val result = Libcoap.client("-v", "7", "-B", "10", "-o", "/data/large.txt", "$uri/large", mount = directory)

        assertEquals(0, result.exitCode, "$result")
        assertEquals(large, output.readText(), "the whole body, block-wise after the repeat")
        val (challenge, repeat) = echoExchange()
        assertEquals(CoapMessageType.ACK, challenge.type, "piggybacked, never a separate Confirmable response")
        assertTrue(challenge.payload.isEmpty())
        assertEquals("/large", repeat.options.path)
        assertTrue(sent.filter { it.code == CoapCode.CONTENT }.all { it.payload.size <= 1024 })
        assertEquals(1, events.count { it is EndpointEvent.LimitReached && it.limit == EndpointEvent.LimitReached.Limit.AMPLIFICATION_FACTOR })
    }

    @Test
    fun completesANonConfirmableRequestAfterANonConfirmableChallenge() {
        val output = File(directory, "large-non.txt")
        val result = Libcoap.client("-N", "-v", "7", "-B", "10", "-o", "/data/large-non.txt", "$uri/large", mount = directory)

        assertEquals(0, result.exitCode, "$result")
        assertEquals(large, output.readText())
        val (challenge, _) = echoExchange()
        assertEquals(CoapMessageType.NON, challenge.type)
    }

    /**
     * RFC 9177 §4.1: libcoap's Q-Block probe, a Confirmable GET `/.well-known/core` with Q-Block2 0/_/16, is
     * answered at once (4.02 while kcoap's Q-Block is off), never challenged; the real request then is.
     */
    @Test
    fun neverChallengesTheQBlockProbe() {
        val output = File(directory, "large-qblock.txt")
        val result = Libcoap.client("-L", "7", "-v", "7", "-B", "10", "-o", "/data/large-qblock.txt", "$uri/large", mount = directory)

        assertEquals(0, result.exitCode, "$result")
        assertEquals(large, output.readText())
        assertTrue("Q-Block support not available" in result.stdout + result.stderr, "$result")
        val probe = received.first { CoapOptions.Q_BLOCK2.number in it.options }
        assertEquals("/.well-known/core", probe.options.path)
        val answer = sent.single { it.hasToken(probe.token) && it.messageId == probe.messageId }
        assertEquals(CoapCode.BAD_OPTION, answer.code, "$answer")
        val (challenge, repeat) = echoExchange()
        assertEquals("/large", repeat.options.path)
        assertContentEquals(challenge.options.echo, repeat.options.echo)
    }
}

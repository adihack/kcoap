package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.server.AccessPolicy
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.RequestCompleted
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

/**
 * [EndpointEvent.RequestCompleted]: one event per request a server finished, with values bounded enough to label a
 * metric (the resource's path in the tree, never the client's Uri-Path) and the time it took on virtual time.
 */
class RequestCompletedTest {
    private val StackFixture.completed: List<RequestCompleted> get() = events.filterIsInstance<RequestCompleted>()

    @Test
    fun aCompletedRequestNamesItsMethodResourceCodeAndDuration() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("sensors") {
                resource("temp") {
                    get {
                        delay(2.seconds)
                        CoapResponse.content("22.5")
                    }
                }
            }
        }
        val client = fixture.client()

        client.get(fixture.uri(server, "/sensors/temp"))

        val completed = fixture.completed.single()
        assertEquals(fixture.address(client.endpoint), completed.peer)
        assertEquals(CoapCode.GET, completed.method)
        assertEquals("/sensors/temp", completed.resourcePath)
        assertEquals(CoapCode.CONTENT, completed.responseCode)
        assertFalse(completed.isSuppressed)
        assertNull(completed.securityIdentity)
        assertEquals(2.seconds, completed.duration)
        assertEquals(
            "RequestCompleted(peer=${completed.peer}, method=0.01 GET, resource=/sensors/temp, response=2.05 Content, " +
                "identity=null, duration=2s)",
            completed.toString(),
        )
    }

    @Test
    fun aPathWithoutAResourceIsNotReportedButARefusedResourceIs() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server {
            resource("private") {
                access = AccessPolicy.MessageSecurityRequired
                get { CoapResponse.content("secret") }
            }
        }
        val client = fixture.client()

        assertEquals(CoapCode.NOT_FOUND, client.get(fixture.uri(server, "/random/a1b2c3")).code)
        assertEquals(CoapCode.UNAUTHORIZED, client.get(fixture.uri(server, "/private")).code)

        // A client's own path never becomes a value, so the set of values stays bounded.
        assertEquals(
            listOf(null to CoapCode.NOT_FOUND, "/private" to CoapCode.UNAUTHORIZED),
            fixture.completed.map { it.resourcePath to it.responseCode },
        )
    }

    @Test
    fun rfc7967ASuppressedResponseIsReportedAsSuppressed() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("lamp") { put { CoapResponse.changed() } } }
        val client = fixture.client(CoapClientConfig { requestTimeout = 5.seconds })

        // RFC 7967 §2.1: 2 suppresses 2.xx; the client hears nothing.
        runCatching {
            client.put(fixture.uri(server, "/lamp"), "on".encodeToByteArray()) {
                nonConfirmable()
                options { noResponse(2) }
            }
        }

        val completed = fixture.completed.single()
        assertEquals(CoapCode.PUT, completed.method)
        assertEquals(CoapCode.CHANGED, completed.responseCode)
        assertTrue(completed.isSuppressed)
        assertTrue("2.04 Changed suppressed" in completed.toString(), completed.toString())
    }

    @Test
    fun rfc7252Section5_4_1ARejectedRequestCompletesWithoutAResponse() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("r") { get { CoapResponse.content("ok") } } }
        val client = fixture.client()

        runCatching {
            client.get(fixture.uri(server, "/r")) {
                nonConfirmable()
                options { add(CoapOption(65001, byteArrayOf(1))) }
            }
        }

        val completed = fixture.completed.single()
        assertEquals("/r", completed.resourcePath)
        assertNull(completed.responseCode, "rejected with a Reset, not answered")
        assertFalse(completed.isSuppressed)
    }

    @Test
    fun aFailingHandlerCompletesWith500() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("broken") { get { error("bug") } } }
        val client = fixture.client()

        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, client.get(fixture.uri(server, "/broken")).code)
        assertEquals(CoapCode.INTERNAL_SERVER_ERROR, fixture.completed.single().responseCode)
    }
}

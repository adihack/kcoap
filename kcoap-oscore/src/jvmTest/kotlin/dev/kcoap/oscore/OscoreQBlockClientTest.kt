package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.QBlockClientConfig
import dev.kcoap.coap.client.QBlockMode
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.server.CoapServer
import dev.kcoap.coap.server.CoapServerConfig
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.QBlockClientEvent
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.InMemoryNetwork
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The Q-Block client (RFC 9177) of kcoap-coap with OSCORE (RFC 8613) protecting its requests. OSCORE accepts
 * one response per request (RFC 8613 §7.4), and Q-Block bodies need several, so until the OSCORE layer takes
 * them with a replay window per request, protected requests use RFC 7959 whatever
 * `QBlockClientConfig.withMessageSecurity` says. On the JVM, where the crypto backend does not suspend, so
 * virtual time does not run under it.
 */
class OscoreQBlockClientTest {
    private val crypto = DefaultCryptoBackend()
    private val qBlockOptions = setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

    @Test
    fun protectedRequestsUseRfc7959EvenWithWithMessageSecuritySet() = runTest {
        val network = InMemoryNetwork(backgroundScope)
        val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()
        val clientContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b)), crypto)
        val serverContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a)), crypto)
        val events = mutableListOf<EndpointEvent>()
        val endpointConfig = CoapEndpointConfig(timeSource = testScheduler.timeSource, eventListener = { events += it })
        val big = ByteArray(3000) { (it % 251).toByte() }
        var stored = ByteArray(0)
        val serverTransport = network.attach()
        val serverSecurity = OscoreServer(InMemoryContextRepository().apply { add(serverContext) })
        CoapServer(serverTransport, backgroundScope, CoapServerConfig(security = serverSecurity), endpointConfig) {
            resource("big") {
                get { CoapResponse.content(big, ContentFormat.OCTET_STREAM) }
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }
        // The requests as the client hands them to OSCORE, before they are encrypted.
        val inner = mutableListOf<CoapMessage>()
        val oscore = OscoreClient(clientContext)
        val recording = object : ClientMessageSecurity {
            override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? {
                inner += request
                return oscore.protectRequest(request, peer)
            }
        }
        val qBlock = QBlockClientConfig(QBlockMode.ASSUME_SUPPORTED, withMessageSecurity = true)
        val endpoint = CoapEndpoint(network.attach(), backgroundScope, endpointConfig)
        val client = CoapClient(endpoint, CoapClientConfig(security = recording, qBlock = qBlock))
        val server = serverTransport.localEndpoint
        val uri = "coap://${server.host}:${server.port}/big"

        val got = client.get(uri) { nonConfirmable() }
        val put = client.put(uri, big.reversedArray()) { nonConfirmable() }

        assertContentEquals(big, got.payload)
        assertEquals(CoapCode.CHANGED, put.code)
        assertContentEquals(big.reversedArray(), stored)
        assertTrue(inner.none { message -> message.options.any { it.number in qBlockOptions } }, "no Q-Block option inside OSCORE")
        assertTrue(inner.any { it.options.block2 != null }, "RFC 7959 carried the response body")
        assertTrue(inner.any { it.options.block1 != null }, "RFC 7959 carried the request body")
        assertTrue(events.none { it is QBlockClientEvent.Fallback }, "nothing was tried and rejected")
    }
}

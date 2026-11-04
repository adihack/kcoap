package dev.kcoap.oscore

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.codec.CoapFormatException
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.common.hexToBytes
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.testkit.ByteMutator
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Hostile OSCORE messages: mutated requests and responses are rejected with [MessageSecurityException]
 * (or passed on when they are not OSCORE at all), never with another exception. A mutation that still
 * verifies would be an AEAD break, so verification successes are checked to be the original message.
 */
class OscoreFuzzTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()

    @Test
    fun optionValuesRejectCleanly() {
        val random = Random(0x05C0)
        val seeds = listOf("0914", "091400", "19140837cbf3210017a2d3", "0100").map { it.hexToBytes() }
        repeat(3_000) { round ->
            val input = ByteMutator.mutate(seeds[round % seeds.size], random)
            try {
                OscoreOptionValue.decode(input)
            } catch (_: OscoreException) {
                // expected
            } catch (e: Throwable) {
                fail("Unexpected ${e::class.simpleName}: $e")
            }
        }
    }

    @Test
    fun mutatedRequestsAndResponsesFailVerification() = runTest {
        val random = Random(0x05C1)
        val clientContext = SecurityContext.derive(SecurityContextParameters(secret, byteArrayOf(1), byteArrayOf(2)), crypto)
        val serverContext = SecurityContext.derive(SecurityContextParameters(secret, byteArrayOf(2), byteArrayOf(1), replayWindowSize = 64), crypto)
        val client = OscoreClient(clientContext)
        val server = OscoreServer(InMemoryContextRepository().apply { add(serverContext) })
        val original = CoapMessage.request(CoapCode.PUT) {
            messageId = 9
            token = byteArrayOf(7)
            options {
                uriPath("a/b")
                contentFormat(0)
            }
            payload("payload")
        }
        repeat(400) {
            val protected = client.protectRequest(original, peer)!!
            val wire = UdpMessageCodec.encode(protected.message)
            val mutated = try {
                UdpMessageCodec.decode(ByteMutator.mutate(wire, random))
            } catch (_: CoapFormatException) {
                return@repeat
            }
            try {
                val verified = server.unprotectRequest(mutated, peer) ?: return@repeat
                if (verified.message != original && verified.challenge == null) {
                    // Only fields OSCORE leaves unprotected (type, Message ID, token, Class U options) may differ.
                    val inner = verified.message
                    if (inner.code != original.code || !inner.payload.contentEquals(original.payload) || inner.options.uriPath != original.options.uriPath) {
                        fail("A mutated request verified with changed protected content: $inner")
                    }
                }
                val response = verified.protectResponse(CoapMessage.response(verified.message, CoapCode.CHANGED) { payload("ok") })
                val responseWire = UdpMessageCodec.encode(response)
                val mutatedResponse = try {
                    UdpMessageCodec.decode(ByteMutator.mutate(responseWire, random))
                } catch (_: CoapFormatException) {
                    return@repeat
                }
                protected.unprotectResponse(mutatedResponse)
            } catch (_: MessageSecurityException) {
                // expected for tampered messages
            } catch (e: Throwable) {
                fail("Unexpected ${e::class.simpleName}: $e")
            }
        }
    }
}

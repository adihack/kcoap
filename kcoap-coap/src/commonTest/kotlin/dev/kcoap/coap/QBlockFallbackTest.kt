package dev.kcoap.coap

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.transport.DatagramEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest

/**
 * Pins how kcoap treats Q-Block (RFC 9177) while it does not support it, which stays the default once it
 * does: RFC 9177 §4.1 has a server without Q-Block answer a Confirmable request carrying Q-Block1 or
 * Q-Block2 with 4.02 (Bad Option) and reject a Non-confirmable one (RFC 7252 §5.4.1), so the peer falls
 * back to RFC 7959; a client without Q-Block rejects a response that carries either option.
 */
class QBlockFallbackTest {
    private fun qBlock1(number: Long, more: Boolean, size: Int): CoapOption =
        CoapOption.uint(CoapOptions.Q_BLOCK1.number, BlockOption(number, more, BlockOption.sizeExponentFor(size)).toUint())

    private fun qBlock2(number: Long, more: Boolean, size: Int): CoapOption =
        CoapOption.uint(CoapOptions.Q_BLOCK2.number, BlockOption(number, more, BlockOption.sizeExponentFor(size)).toUint())

    @Test
    fun rfc9177Section4_1AConfirmableQBlock1RequestGets402WithADiagnostic() = runTest {
        val fixture = StackFixture(this)
        var stored: ByteArray? = null
        val server = fixture.server {
            resource("store") {
                put {
                    stored = payload
                    CoapResponse.changed()
                }
            }
        }
        val client = fixture.client()

        val response = client.put(fixture.uri(server, "/store"), ByteArray(64) { it.toByte() }) {
            options {
                add(qBlock1(0, true, 64))
                size1(3000)
                requestTag(byteArrayOf(1, 2, 3, 4))
            }
        }

        assertEquals(CoapCode.BAD_OPTION, response.code)
        assertEquals("Unrecognized critical option 19", response.payloadAsText(), "RFC 7252 §5.5.2 diagnostic payload")
        assertEquals(null, stored, "the handler never sees the block")
        val answer = fixture.traffic.mapNotNull { it.message }.single { it.isResponse }
        assertEquals(CoapMessageType.ACK, answer.type, "piggybacked")
        assertTrue(CoapOptions.Q_BLOCK1.number !in answer.options && CoapOptions.Q_BLOCK2.number !in answer.options)
    }

    @Test
    fun rfc9177Section4_1AConfirmableQBlock2RequestGets402WithADiagnostic() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("big") { get { CoapResponse.content("x".repeat(3000)) } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/big")) { options { add(qBlock2(0, true, 64)) } }

        assertEquals(CoapCode.BAD_OPTION, response.code)
        assertEquals("Unrecognized critical option 31", response.payloadAsText())
    }

    @Test
    fun rfc9177Section4_1ALibcoapStyleProbeOfWellKnownCoreGets402() = runTest {
        // libcoap's coap-client -L 7 probes with a Confirmable GET of /.well-known/core carrying Q-Block2 0/0/16;
        // the 4.02 tells it that Q-Block is not available, and it sends its real request with Block2.
        val fixture = StackFixture(this)
        val server = fixture.server { resource("hello") { get { CoapResponse.content("hi") } } }
        val client = fixture.client()

        val probe = client.get(fixture.uri(server, "/.well-known/core")) { options { add(qBlock2(0, false, 16)) } }

        assertEquals(CoapCode.BAD_OPTION, probe.code)
        assertEquals("Unrecognized critical option 31", probe.payloadAsText())
        assertEquals("hi", client.get(fixture.uri(server, "/hello")).payloadAsText(), "the fallback request is served")
    }

    @Test
    fun rfc9177Section4_1AMissingBlockRequestWithRepeatedQBlock2Gets402() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("big") { get { CoapResponse.content("x".repeat(3000)) } } }
        val client = fixture.client()

        val response = client.get(fixture.uri(server, "/big")) {
            options {
                add(qBlock2(2, false, 64))
                add(qBlock2(7, false, 64))
            }
        }

        assertEquals(CoapCode.BAD_OPTION, response.code)
        assertEquals("Unrecognized critical option 31, 31", response.payloadAsText())
    }

    @Test
    fun rfc7252Section5_4_1ANonConfirmableQBlock1RequestIsRejected() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("store") { put { CoapResponse.changed() } } }
        val client = fixture.client()

        val error = assertIs<CoapException>(
            runCatching {
                client.put(fixture.uri(server, "/store"), ByteArray(64)) {
                    nonConfirmable()
                    options {
                        add(qBlock1(0, true, 64))
                        size1(3000)
                        requestTag(byteArrayOf(9))
                    }
                }
            }.exceptionOrNull(),
        )

        assertEquals(CoapException.Reason.RESET, error.reason)
        val wire = fixture.traffic.mapNotNull { it.message }
        assertEquals(listOf(CoapMessageType.NON, CoapMessageType.RST), wire.map { it.type })
        assertEquals(wire[0].messageId, wire[1].messageId)
    }

    @Test
    fun rfc7252Section5_4_1ANonConfirmableQBlock2RequestIsRejected() = runTest {
        val fixture = StackFixture(this)
        val server = fixture.server { resource("big") { get { CoapResponse.content("x".repeat(3000)) } } }
        val client = fixture.client()

        val error = assertIs<CoapException>(
            runCatching {
                client.get(fixture.uri(server, "/big")) {
                    nonConfirmable()
                    options { add(qBlock2(0, true, 64)) }
                }
            }.exceptionOrNull(),
        )

        assertEquals(CoapException.Reason.RESET, error.reason)
        assertEquals(listOf(CoapMessageType.NON, CoapMessageType.RST), fixture.traffic.mapNotNull { it.message?.type })
    }

    @Test
    fun rfc7252Section5_4_1AClientWithoutQBlockRejectsAQBlock2Response() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        val pending = async {
            runCatching { client.get("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/big") }
        }
        val event = transport.receive() as DatagramEvent.Received
        val request = UdpMessageCodec.decode(event.datagram)
        assertTrue(CoapOptions.Q_BLOCK2.number !in request.options, "the client does not ask for Q-Block")
        val response = CoapMessage.response(request, CoapCode.CONTENT) {
            type = CoapMessageType.ACK
            messageId = request.messageId
            options {
                etag(byteArrayOf(1))
                add(qBlock2(0, true, 64))
                size2(3000)
            }
            payload = ByteArray(64)
        }
        transport.send(event.from, UdpMessageCodec.encode(response))

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
        assertEquals("Response with unrecognized critical options 31", error.message)
    }

    @Test
    fun rfc7252Section5_4_1AClientWithoutQBlockRejectsAQBlock1Response() = runTest {
        val fixture = StackFixture(this)
        val transport = fixture.transport()
        val client = fixture.client()
        val pending = async {
            runCatching { client.put("coap://${transport.localEndpoint.host}:${transport.localEndpoint.port}/store", ByteArray(10)) }
        }
        val event = transport.receive() as DatagramEvent.Received
        val request = UdpMessageCodec.decode(event.datagram)
        val response = CoapMessage.response(request, CoapCode.CONTINUE) {
            type = CoapMessageType.ACK
            messageId = request.messageId
            options { add(qBlock1(0, true, 16)) }
        }
        transport.send(event.from, UdpMessageCodec.encode(response))

        val error = assertIs<CoapException>(pending.await().exceptionOrNull())
        assertEquals(CoapException.Reason.BAD_RESPONSE, error.reason)
    }
}

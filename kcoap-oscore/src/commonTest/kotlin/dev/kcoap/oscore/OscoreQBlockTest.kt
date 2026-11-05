package dev.kcoap.oscore

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.readUnsigned
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Several responses to one request (RFC 9177 under RFC 8613), through the stack-facing SPI: a request that
 * carries an Inner Q-Block1 or Q-Block2 option takes the responses of its body, at most one with the request's
 * nonce and the others each with a Partial IV new in the request's replay window; every other request still
 * takes one response (RFC 8613 §7.4). The kcoap-to-kcoap bodies over the stack are in `OscoreQBlockEndToEndTest`
 * (jvmTest).
 */
class OscoreQBlockTest {
    private val crypto = DefaultCryptoBackend()
    private val peer = Endpoint.Udp("192.0.2.1", 5683)
    private val secret = "0102030405060708090a0b0c0d0e0f10".hexToBytes()

    private suspend fun clientFor(replayWindowSize: Int = 32): Pair<OscoreClient, OscoreServer> {
        val client = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b), replayWindowSize = replayWindowSize), crypto)
        val server = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a)), crypto)
        return OscoreClient(client) to OscoreServer(InMemoryContextRepository().apply { add(server) })
    }

    /** A GET that asks for its response body in Q-Block2 blocks of 64 bytes (RFC 9177 §4.4). */
    private fun download(): CoapMessage = CoapMessage.request(CoapCode.GET) {
        messageId = 1
        token = "b1".hexToBytes()
        options {
            uriPath("big")
            qBlock2(BlockOption(0, true, 2))
        }
    }

    /** Block [num] of a Q-Block1 upload of 1000 bytes in blocks of 64 (RFC 9177 §4.3). */
    private fun uploadBlock(num: Long): CoapMessage = CoapMessage.request(CoapCode.PUT) {
        messageId = 2
        token = "c1".hexToBytes()
        options {
            uriPath("store")
            qBlock1(BlockOption(num, true, 2))
            size1(1000)
            requestTag("01020304".hexToBytes())
        }
        payload = ByteArray(64) { num.toByte() }
    }

    /** A GET of the second block of a body with Block2 of RFC 7959, which takes one response. */
    private fun rfc7959Block(): CoapMessage = CoapMessage.request(CoapCode.GET) {
        messageId = 3
        token = "d1".hexToBytes()
        options {
            uriPath("big")
            block2(BlockOption(1, false, 2))
        }
    }

    private fun block(request: CoapMessage, num: Long, more: Boolean = true): CoapMessage =
        CoapMessage.response(request, CoapCode.CONTENT) {
            options {
                etag("e7".hexToBytes())
                qBlock2(BlockOption(num, more, 2))
                size2(1000)
                contentFormat(ContentFormat.OCTET_STREAM)
            }
            payload = ByteArray(64) { num.toByte() }
        }

    private fun partialIvOf(protected: CoapMessage): Long? =
        OscoreOptionValue.decode(protected.options.oscore!!).partialIv?.readUnsigned()

    private class Exchange(val client: ProtectedRequest, val server: UnprotectedRequest)

    private suspend fun exchange(client: OscoreClient, server: OscoreServer, request: CoapMessage): Exchange {
        val protected = client.protectRequest(request, peer)!!
        return Exchange(protected, server.unprotectRequest(protected.message, peer)!!)
    }

    @Test
    fun rfc9177Section4QBlockOptionsGoInsideOscore() = runTest {
        val (client, server) = clientFor()
        val download = client.protectRequest(download(), peer)!!
        val upload = client.protectRequest(uploadBlock(3), peer)!!

        // Table 2: Q-Block1 and Q-Block2 are Class E and U; an endpoint that is not a proxy puts them inside.
        for (outer in listOf(download.message, upload.message)) {
            assertNull(outer.options.qBlock1, "no Outer Q-Block1")
            assertNull(outer.options.qBlock2, "no Outer Q-Block2")
            assertTrue(CoapOptions.REQUEST_TAG.number !in outer.options && CoapOptions.SIZE1.number !in outer.options)
        }
        assertEquals(BlockOption(0, true, 2), server.unprotectRequest(download.message, peer)!!.message.options.qBlock2)
        val block = server.unprotectRequest(upload.message, peer)!!.message
        assertEquals(BlockOption(3, true, 2), block.options.qBlock1)
        assertEquals(1000, block.options.size1)
    }

    @Test
    fun rfc9177Section4_4AQBlock2RequestTakesTheBlocksOfASetInAnyOrder() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, download())
        val inner = exchange.server.message
        // The server protects the first response with the request's nonce, every later one with a new Partial IV.
        val set = (0L until 10L).map { exchange.server.protectResponse(block(inner, it)) }
        assertNull(partialIvOf(set[0]), "block 0 reuses the request's nonce")
        assertTrue(set.drop(1).all { partialIvOf(it) != null }, "blocks 1-9 carry Partial IVs")

        for (num in listOf(3, 0, 1, 9, 2, 8, 4, 7, 5, 6)) {
            val block = exchange.client.unprotectResponse(set[num])
            assertEquals(BlockOption(num.toLong(), true, 2), block.options.qBlock2)
            assertContentEquals(ByteArray(64) { num.toByte() }, block.payload)
        }
    }

    @Test
    fun rfc8613Section7_4AReplayedBlockResponseIsRejected() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, download())
        val inner = exchange.server.message
        val first = exchange.server.protectResponse(block(inner, 0))
        val second = exchange.server.protectResponse(block(inner, 1))
        val third = exchange.server.protectResponse(block(inner, 2))
        exchange.client.unprotectResponse(first)
        exchange.client.unprotectResponse(second)

        val replay = assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(second) }
        assertEquals("Replayed or too old response", replay.message)
        val nonceAgain = assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(first) }
        assertEquals("Second response without a Partial IV", nonceAgain.message, "the request's nonce serves one response")
        // The body goes on: the replays changed nothing.
        assertEquals(BlockOption(2, true, 2), exchange.client.unprotectResponse(third).options.qBlock2)
    }

    @Test
    fun rfc8613Section7_4ASecondResponseToARequestWithoutQBlockIsStillRejected() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, rfc7959Block())
        val inner = exchange.server.message
        val response = CoapMessage.response(inner, CoapCode.CONTENT) {
            options { block2(BlockOption(1, true, 2)) }
            payload = ByteArray(64)
        }
        val first = exchange.server.protectResponse(response)
        val second = exchange.server.protectResponse(response)
        assertNull(partialIvOf(first))
        assertNotNull(partialIvOf(second), "a second response would take a new Partial IV")

        exchange.client.unprotectResponse(first)
        val rejected = assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(second) }
        assertEquals("Second response to a request that takes one", rejected.message)
        assertFailsWith<MessageSecurityException>("nor the first again") { exchange.client.unprotectResponse(first) }
    }

    @Test
    fun rfc8613Section7_4ASecondResponseIsRejectedAlsoWhenTheFirstHadAPartialIv() = runTest {
        // §8.3 step 3 lets the server protect even the first response with a new Partial IV; one response it stays.
        val clientContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0a), byteArrayOf(0x0b)), crypto)
        val serverContext = ephemeralContext(SecurityContextParameters(secret, byteArrayOf(0x0b), byteArrayOf(0x0a)), crypto)
        val protected = OscoreClient(clientContext).protectRequest(rfc7959Block(), peer)!!
        val verified = OscoreProtection.unprotectRequest(protected.message) { _, _ -> serverContext }
        assertFalse(verified.binding.allowsSeveralResponses, "Block2 of RFC 7959 takes one response per block")
        val response = CoapMessage.response(verified.message, CoapCode.CONTENT) { payload("one") }
        val first = OscoreProtection.protectResponse(verified.binding, response, newPartialIv = true)
        val second = OscoreProtection.protectResponse(verified.binding, response, newPartialIv = true)

        assertEquals("one", protected.unprotectResponse(first).payloadAsText())
        assertFailsWith<MessageSecurityException>("a second response with a new Partial IV") { protected.unprotectResponse(second) }
        assertFailsWith<MessageSecurityException>("the first one replayed") { protected.unprotectResponse(first) }
    }

    @Test
    fun rfc9177Section4_3AQBlock1BlockTakesItsFourEightsAndTheFinalResponse() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, uploadBlock(9))
        val inner = exchange.server.message
        // Figure 6: 4.08 lists on the token of the highest block on the NON_RECEIVE_TIMEOUT ladder, then the end.
        val missing = List(4) {
            exchange.server.protectResponse(
                CoapMessage.response(inner, CoapCode.REQUEST_ENTITY_INCOMPLETE) {
                    options { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
                    payload = byteArrayOf(0x01)
                },
            )
        }
        val changed = exchange.server.protectResponse(CoapMessage.response(inner, CoapCode.CHANGED))

        for (response in missing) assertEquals(CoapCode.REQUEST_ENTITY_INCOMPLETE, exchange.client.unprotectResponse(response).code)
        assertEquals(CoapCode.CHANGED, exchange.client.unprotectResponse(changed).code)
        assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(missing[2]) }
    }

    @Test
    fun rfc6347Section4_1_2_6ABlockFurtherBehindThanTheWindowIsRejected() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, download())
        val inner = exchange.server.message
        exchange.client.unprotectResponse(exchange.server.protectResponse(block(inner, 0)))
        val late = exchange.server.protectResponse(block(inner, 1))
        // 32 more responses: block 1's Partial IV is 32 behind the newest, outside the default window.
        val later = (2L..33L).map { exchange.server.protectResponse(block(inner, it % 10)) }
        exchange.client.unprotectResponse(later.last())
        exchange.client.unprotectResponse(later.first())

        assertFailsWith<MessageSecurityException>("too old for the window") { exchange.client.unprotectResponse(late) }
    }

    @Test
    fun theResponseWindowHasTheSizeOfTheClientsContextWindow() = runTest {
        val (client, server) = clientFor(replayWindowSize = 64)
        val exchange = exchange(client, server, download())
        val inner = exchange.server.message
        exchange.client.unprotectResponse(exchange.server.protectResponse(block(inner, 0)))
        val late = exchange.server.protectResponse(block(inner, 1))
        val later = (2L..33L).map { exchange.server.protectResponse(block(inner, it % 10)) }
        exchange.client.unprotectResponse(later.last())

        assertEquals(BlockOption(1, true, 2), exchange.client.unprotectResponse(late).options.qBlock2, "within 64")
    }

    @Test
    fun aForgedBlockChangesNothing() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, download())
        val inner = exchange.server.message
        val first = exchange.server.protectResponse(block(inner, 0))
        val second = exchange.server.protectResponse(block(inner, 1))
        fun forged(message: CoapMessage) = message.toBuilder().apply { payload = payload.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() } }.build()

        assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(forged(first)) }
        assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(forged(second)) }

        // Neither the request's nonce nor block 1's Partial IV counts as used.
        assertEquals(BlockOption(0, true, 2), exchange.client.unprotectResponse(first).options.qBlock2)
        assertEquals(BlockOption(1, true, 2), exchange.client.unprotectResponse(second).options.qBlock2)
    }

    @Test
    fun rfc8613Section4_1_3_5_2AnObserveRegistrationTakesOneResponseThatIsNotANotification() = runTest {
        val (client, server) = clientFor()
        val registration = CoapMessage.request(CoapCode.GET) {
            token = "e1".hexToBytes()
            options {
                uriPath("big")
                observe(0)
            }
        }
        val exchange = exchange(client, server, registration)
        val inner = exchange.server.message
        suspend fun notification(value: Long) =
            exchange.server.protectResponse(CoapMessage.response(inner, CoapCode.CONTENT) { options { observe(value) } })
        assertNotNull(exchange.client.unprotectResponse(notification(5)).options.observe)
        assertNotNull(exchange.client.unprotectResponse(notification(6)).options.observe)
        // Without an Inner Observe option the response is verified as a non-Observe response: it ends the observation.
        val last = exchange.server.protectResponse(CoapMessage.response(inner, CoapCode.NOT_FOUND))
        val another = exchange.server.protectResponse(CoapMessage.response(inner, CoapCode.NOT_FOUND))
        assertEquals(CoapCode.NOT_FOUND, exchange.client.unprotectResponse(last).code)

        val second = assertFailsWith<MessageSecurityException> { exchange.client.unprotectResponse(another) }
        assertEquals("Second response to a request that takes one", second.message)
        assertFailsWith<MessageSecurityException>("nor the first again") { exchange.client.unprotectResponse(last) }
        assertNotNull(exchange.client.unprotectResponse(notification(7)).options.observe, "a notification is still accepted")
    }

    // ---------------------------------------------------------------- notification bodies (RFC 9177 §4.5)

    /** An Observe registration of `/obs` that asks for its notification bodies in Q-Block2 blocks of 64 bytes. */
    private fun qBlockRegistration(): CoapMessage = CoapMessage.request(CoapCode.GET) {
        token = "f1".hexToBytes()
        options {
            uriPath("obs")
            observe(0)
            qBlock2(BlockOption(0, true, 2))
        }
    }

    /** A FETCH registration uploaded in Q-Block1 (RFC 9177 Figure 14): its block that completed the upload. */
    private fun qBlock1FetchRegistration(): CoapMessage = CoapMessage.request(CoapCode.FETCH) {
        token = "f2".hexToBytes()
        options {
            uriPath("obs")
            observe(0)
            qBlock1(BlockOption(1, false, 2))
            size1(100)
            requestTag("0a0b0c0d".hexToBytes())
        }
        payload = ByteArray(36)
    }

    /** Block [num] of the notification body with [etag], of [count] blocks; every block carries Observe (§4.5). */
    private fun notificationBlock(registration: CoapMessage, etag: Int, num: Long, count: Long = 10): CoapMessage =
        CoapMessage.response(registration, CoapCode.CONTENT) {
            options {
                observe(etag.toLong())
                etag(byteArrayOf(etag.toByte()))
                qBlock2(BlockOption(num, num < count - 1, 2))
                size2(count * 64)
            }
            payload = ByteArray(64) { num.toByte() }
        }

    /** Every block of the notification body with [etag], protected by the server in the order it sends them. */
    private suspend fun Exchange.body(etag: Int, count: Long = 10): List<CoapMessage> =
        (0L until count).map { server.protectResponse(notificationBlock(server.message, etag, it, count)) }

    private suspend fun Exchange.rejection(response: CoapMessage): String? =
        assertFailsWith<MessageSecurityException> { client.unprotectResponse(response) }.message

    @Test
    fun rfc9177Section4_5TheBlocksOfANotificationBodyAreTakenInAnyOrder() = runTest {
        for (registration in listOf(qBlockRegistration(), qBlock1FetchRegistration())) {
            val (client, server) = clientFor()
            val exchange = exchange(client, server, registration)
            // The registration's own body: block 0 reuses the request's nonce, every later block has a Partial IV.
            val first = exchange.body(etag = 1)
            val second = exchange.body(etag = 2)
            assertNull(partialIvOf(first[0]))
            assertTrue((first.drop(1) + second).all { partialIvOf(it) != null }, "every other block is a notification of its own")

            val order = listOf(3, 0, 1, 9, 2, 8, 4, 7, 5, 6)
            for (num in order) {
                val block = exchange.client.unprotectResponse(first[num])
                assertEquals(num.toLong(), block.options.qBlock2!!.number, "${registration.code}: block $num of the first body")
                assertEquals(if (num == 0) 0L else partialIvOf(first[num])!! + 1, block.options.observe, "Observe from the Partial IV")
            }
            for (num in order.reversed()) {
                val block = exchange.client.unprotectResponse(second[num])
                assertContentEquals(byteArrayOf(2), block.options.etags.single(), "${registration.code}: block $num of the second body")
            }
        }
    }

    @Test
    fun rfc8613Section7_4_1AReplayedBlockOfANotificationBodyIsStillRejected() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val body = exchange.body(etag = 1)
        for (num in listOf(0, 4, 2, 1)) exchange.client.unprotectResponse(body[num])

        assertEquals("Replayed or stale notification", exchange.rejection(body[2]), "below the highest, received before")
        assertEquals("Replayed or stale notification", exchange.rejection(body[4]), "the highest, received before")
        assertEquals("Second notification without a Partial IV", exchange.rejection(body[0]))
        // The replays changed nothing: the rest of the body is still taken, in any order.
        for (num in listOf(9, 3, 5, 8, 6, 7)) assertEquals(num.toLong(), exchange.client.unprotectResponse(body[num]).options.qBlock2!!.number)
    }

    @Test
    fun rfc8613Section4_1_3_5_2ABlockOfAnOlderBodyIsStaleOnceANewerBodyArrived() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val older = exchange.body(etag = 1)
        val newer = exchange.body(etag = 2)
        exchange.client.unprotectResponse(older[1])
        exchange.client.unprotectResponse(older[3])
        exchange.client.unprotectResponse(newer[5])

        // §4.1.3.5.2: the highest Partial IV is the freshest; block 2 of the older body was never received.
        assertEquals("Stale notification of an older body", exchange.rejection(older[2]))
        assertEquals("Stale notification without a Partial IV", exchange.rejection(older[0]), "the oldest of all")
        // Blocks of the newer body below its highest are still taken: they belong to the freshest notification.
        for (num in listOf(0, 4, 1, 3, 2)) exchange.client.unprotectResponse(newer[num])
        assertEquals("Stale notification of an older body", exchange.rejection(older[9]))
    }

    @Test
    fun rfc8613Section7_4_1AStaleNotificationIsAReplayOnceItsBodyBecameTheFreshest() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val older = exchange.body(etag = 1)
        val newer = exchange.body(etag = 2)
        // A block of the older body protected after the newer body: a set of it still going out when the newer
        // body began, or a server that used its ETag again.
        val late = exchange.server.protectResponse(notificationBlock(exchange.server.message, etag = 1, num = 5))
        exchange.client.unprotectResponse(newer[3])
        assertEquals("Stale notification of an older body", exchange.rejection(older[2]))
        assertEquals("Stale notification without a Partial IV", exchange.rejection(older[0]))

        // The late block holds the highest Partial IV, so the older body is the freshest again (§4.1.3.5.2), and
        // its blocks never received are taken.
        assertEquals(5L, exchange.client.unprotectResponse(late).options.qBlock2!!.number)
        assertEquals(4L, exchange.client.unprotectResponse(older[4]).options.qBlock2!!.number)
        // The stale ones verified, so they were received: sent again they are replays, freshest body or not.
        assertEquals("Replayed or stale notification", exchange.rejection(older[2]))
        assertEquals("Second notification without a Partial IV", exchange.rejection(older[0]))
    }

    @Test
    fun rfc7641Section3_4AStaleWholeBodyIsNeverTaken() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val first = exchange.body(etag = 1)
        val stale = exchange.body(etag = 2)
        val newest = exchange.body(etag = 3)
        for (block in first + newest) exchange.client.unprotectResponse(block)

        // Held back on the path and delivered after the newer body: each of its blocks is refused, those within
        // the window as stale and the older ones as too old to tell from a replay.
        val rejections = stale.map { exchange.rejection(it) }
        assertTrue(rejections.all { it == "Stale notification of an older body" || it == "Replayed or stale notification" }, "$rejections")
        assertTrue("Stale notification of an older body" in rejections, "within the window: $rejections")
    }

    @Test
    fun rfc8613Section4_1_3_5_2ANotificationOfOneMessageBelowTheFreshestIsStale() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val inner = exchange.server.message
        suspend fun single(value: Long) = exchange.server.protectResponse(
            CoapMessage.response(inner, CoapCode.CONTENT) {
                options { observe(value) }
                payload("state $value")
            },
        )
        val first = single(1)
        val older = single(2)
        val body = exchange.body(etag = 3)
        val newer = single(4)
        exchange.client.unprotectResponse(first)
        exchange.client.unprotectResponse(body[2])

        assertEquals("Stale notification of an older body", exchange.rejection(older), "older than the body in progress")
        exchange.client.unprotectResponse(body[1])
        assertEquals("state 4", exchange.client.unprotectResponse(newer).payloadAsText())
        assertEquals("Stale notification of an older body", exchange.rejection(body[3]), "the body was superseded by a newer one")
    }

    @Test
    fun rfc6347Section4_1_2_6ABlockOfTheFreshestBodyFurtherBehindThanTheWindowIsRejected() = runTest {
        val (client, server) = clientFor()
        val exchange = exchange(client, server, qBlockRegistration())
        val body = exchange.body(etag = 1, count = 40)
        exchange.client.unprotectResponse(body[39])

        assertEquals("Replayed or stale notification", exchange.rejection(body[7]), "32 behind the highest, outside the window")
        assertEquals(8L, exchange.client.unprotectResponse(body[8]).options.qBlock2!!.number, "31 behind, inside it")
    }

    @Test
    fun rfc8613Section7_4_1ARegistrationWithoutQBlockKeepsTheStrictOrder() = runTest {
        val (client, server) = clientFor()
        val registration = CoapMessage.request(CoapCode.GET) {
            token = "e3".hexToBytes()
            options {
                uriPath("obs")
                observe(0)
            }
        }
        val exchange = exchange(client, server, registration)
        val inner = exchange.server.message
        val notifications = (1L..4L).map { value ->
            exchange.server.protectResponse(CoapMessage.response(inner, CoapCode.CONTENT) { options { observe(value) } })
        }
        exchange.client.unprotectResponse(notifications[2])

        // §7.4.1 lets an application take only Partial IVs above the Notification Number: neither the one never
        // received below it, nor the registration's answer without a Partial IV, which counts as the oldest.
        assertEquals("Replayed or stale notification", exchange.rejection(notifications[1]))
        assertEquals("Stale notification without a Partial IV", exchange.rejection(notifications[0]))
        assertEquals("Replayed or stale notification", exchange.rejection(notifications[2]))
        assertNotNull(exchange.client.unprotectResponse(notifications[3]).options.observe)
    }

    /** An unprotected response to [request] as it went on the wire, sent by anyone who saw its token. */
    private fun unprotected(request: ProtectedRequest, code: CoapCode, block: CoapMessage.Builder.() -> Unit = {}): CoapMessage =
        CoapMessage.response(request.message, code) {
            type = CoapMessageType.NON
            block()
        }

    /** A response the Q-Block client acts on in a body, and why OSCORE drops it unprotected to a Q-Block request. */
    private class Control(val name: String, val response: CoapMessage, val rejection: String)

    /** Each [Control], sent unprotected to a request of [exchange]. */
    private fun bodyControls(exchange: Exchange): List<Control> = listOf(
        Control(
            "a 4.08 listing blocks 0-9",
            unprotected(exchange.client, CoapCode.REQUEST_ENTITY_INCOMPLETE) {
                options { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
                payload = ByteArray(10) { it.toByte() }
            },
            "Unprotected 4.08 Request Entity Incomplete to a Q-Block request",
        ),
        Control(
            "a 4.13 for blocks of 16 bytes",
            unprotected(exchange.client, CoapCode.REQUEST_ENTITY_TOO_LARGE) {
                options {
                    qBlock1(BlockOption(0, true, 0))
                    size1(16)
                }
            },
            "Unprotected 4.13 Request Entity Too Large to a Q-Block request",
        ),
        Control(
            "a 4.01 with Echo",
            unprotected(exchange.client, CoapCode.UNAUTHORIZED) { options { echo("0102030405060708".hexToBytes()) } },
            "Unprotected 4.01 Unauthorized with Echo to a Q-Block request",
        ),
        Control(
            "a 4.02 before any answer",
            unprotected(exchange.client, CoapCode.BAD_OPTION) { payload("Unrecognized critical option 19") },
            "Unprotected 4.02 Bad Option to a Q-Block request",
        ),
        Control(
            "a 4.00 carrying a block",
            unprotected(exchange.client, CoapCode.BAD_REQUEST) {
                options { qBlock2(BlockOption(0, true, 2)) }
                payload = ByteArray(64)
            },
            "Unprotected 4.00 Bad Request with a block option to a Q-Block request",
        ),
        Control(
            "a 5.03 carrying a Block2",
            unprotected(exchange.client, CoapCode.SERVICE_UNAVAILABLE) { options { block2(BlockOption(0, true, 2)) } },
            "Unprotected 5.03 Service Unavailable with a block option to a Q-Block request",
        ),
        Control(
            "a 2.31",
            unprotected(exchange.client, CoapCode.CONTINUE) { options { qBlock1(BlockOption(9, true, 2)) } },
            "Unprotected response to an OSCORE request",
        ),
    )

    @Test
    fun rfc9177Section11UnprotectedResponsesThatWouldSteerAQBlockBodyAreDropped() = runTest {
        val (client, server) = clientFor()
        val upload = exchange(client, server, uploadBlock(9))
        val download = exchange(client, server, download())

        for (exchange in listOf(upload, download)) {
            for (control in bodyControls(exchange)) {
                val dropped = assertFailsWith<MessageSecurityException>(control.name) { exchange.client.unprotectResponse(control.response) }
                assertEquals(control.rejection, dropped.message, control.name)
            }
        }
        // Nothing of either body changed: their protected responses are taken as before.
        val changed = upload.server.protectResponse(CoapMessage.response(upload.server.message, CoapCode.CHANGED))
        assertEquals(CoapCode.CHANGED, upload.client.unprotectResponse(changed).code)
        val first = download.server.protectResponse(block(download.server.message, 0))
        assertEquals(BlockOption(0, true, 2), download.client.unprotectResponse(first).options.qBlock2)
    }

    @Test
    fun rfc8613Section8_2OscoreErrorsToAQBlockRequestStillPassUnprotected() = runTest {
        val (client, server) = clientFor()
        val upload = exchange(client, server, uploadBlock(9))
        // Context not found or replay (4.01), decryption failed (4.00), an overloaded server or an intermediary (5.xx).
        val errors = listOf(
            unprotected(upload.client, CoapCode.UNAUTHORIZED) { payload("Security context not found") },
            unprotected(upload.client, CoapCode.BAD_REQUEST) { payload("Decryption failed") },
            unprotected(upload.client, CoapCode.SERVICE_UNAVAILABLE) { options { maxAge(2) } },
            unprotected(upload.client, CoapCode.of(5, 4)),
        )

        for (error in errors) assertSame(error, upload.client.unprotectResponse(error), "${error.code} ends the body")
    }

    @Test
    fun unprotectedErrorsToARequestWithoutQBlockAreStillPassedOn() = runTest {
        val (client, server) = clientFor()
        val plain = exchange(client, server, rfc7959Block())

        for (control in bodyControls(plain)) {
            val response = control.response
            if (response.code.isSuccess) {
                val dropped = assertFailsWith<MessageSecurityException>(control.name) { plain.client.unprotectResponse(response) }
                assertEquals("Unprotected response to an OSCORE request", dropped.message)
            } else {
                assertSame(response, plain.client.unprotectResponse(response), "${control.name}, unauthenticated, for the application")
            }
        }
    }
}

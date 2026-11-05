package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The example figures of RFC 9177 (§10, Appendices A and B) as scripts on virtual time: the client and
 * server state machines run over [QBlockWire], the figure's messages are dropped where the figure loses
 * them, and the wire must show the figure's sequence, block numbers, M bits, tokens and times.
 *
 * Tokens are numbered in the order the client creates them (`T0`, `T1`, …) where the figures use hex
 * labels; ETags count from 0x10 and Observe values from 1000. NON_TIMEOUT_RANDOM is 2.5 s and
 * NON_RECEIVE_TIMEOUT 4 s. A burst of blocks is on the wire before any answer to it arrives, which is where
 * Figures 4 and 16 draw some messages in another order.
 */
class QBlockFiguresTest {
    private fun body(size: Int, seed: Int = size): ByteArray = Random(seed).nextBytes(size)

    private fun QBlockWire.lines(from: Int = 0): List<String> = trace.drop(from).map { it.toString() }

    private fun blocks(range: IntRange, line: (Int) -> String): List<String> = range.map(line)

    /** Drops server blocks of the body with ETag [etag]: copy n of block NUM is dropped if n is in drops[NUM]. */
    private fun serverBlockDrops(etag: Int, drops: Map<Long, Set<Int>>): (QBlockWire.Line) -> Boolean {
        val copies = HashMap<Long, Int>()
        return { line ->
            val message = line.message
            val num = message.options.qBlock2?.number
            if (line.fromClient || num == null || message.options.etags.singleOrNull()?.get(0)?.toInt() != etag) {
                false
            } else {
                val copy = (copies[num] ?: 0) + 1
                copies[num] = copy
                copy in (drops[num] ?: emptySet())
            }
        }
    }

    // ---------------------------------------------------------------- §10.1: Q-Block1 with NON

    @Test
    fun rfc9177Figure2NonQBlock1WithoutLoss() {
        val wire = QBlockWire()
        val upload = body(3 * 1024 + 500)

        wire.client.upload(upload)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf(
                "0s C>S NON PUT T0 QB1:0/1/1024",
                "0s C>S NON PUT T1 QB1:1/1/1024",
                "0s C>S NON PUT T2 QB1:2/1/1024",
                "0s C>S NON PUT T3 QB1:3/0/1024",
                "0s S>C NON 2.04 T3",
            ),
            wire.lines(),
        )
        val requests = wire.trace.filter { it.fromClient }.map { it.message.options }
        assertTrue(requests.all { it.requestTags.single().contentEquals(byteArrayOf(9)) }, "one Request-Tag for the body")
        assertTrue(requests.all { it.size1 == upload.size.toLong() }, "§4.6: Size1 on every block")
        assertContentEquals(upload, wire.server.received.single().payload)
        assertEquals(CoapCode.CHANGED, wire.client.delivered.single().code)
        assertEquals(setOf(0, 1, 2), wire.client.released, "every token but the final response's is released")
    }

    @Test
    fun rfc9177Figure3ContinueSkipsThePause() {
        val wire = QBlockWire()
        val upload = body(10 * 1024 + 100)

        wire.client.upload(upload)
        wire.runUntil(300.seconds)

        assertEquals(
            blocks(0..9) { "0s C>S NON PUT T$it QB1:$it/1/1024" } + listOf(
                "0s S>C NON 2.31 T9 QB1:9/1/1024",
                "0s C>S NON PUT T10 QB1:10/0/1024",
                "0s S>C NON 2.04 T10",
            ),
            wire.lines(),
        )
        assertContentEquals(upload, wire.server.received.single().payload)
        assertEquals((0..9).toSet(), wire.client.released, "the 2.31 confirmed blocks 0 to 9")
    }

    /**
     * Figures 4 and 5. The figure draws block 12 after the resent blocks 1 and 9, because the 4.08 reaches
     * the client in the middle of the second set; sent as a burst, block 12 is on the wire first.
     */
    @Test
    fun rfc9177Figure4And5RecoveryAcrossSets() {
        val wire = QBlockWire()
        val upload = body(12 * 1024 + 200)
        wire.drop = { it.fromClient && it.num in setOf(1L, 9L, 10L) && it.copy == 1 }

        wire.client.upload(upload)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("0s C>S NON PUT T0 QB1:0/1/1024", "X 0s C>S NON PUT T1 QB1:1/1/1024") +
                blocks(2..8) { "0s C>S NON PUT T$it QB1:$it/1/1024" } + listOf(
                    "X 0s C>S NON PUT T9 QB1:9/1/1024",
                    "X 2.5s C>S NON PUT T10 QB1:10/1/1024",
                    "2.5s C>S NON PUT T11 QB1:11/1/1024",
                    "2.5s C>S NON PUT T12 QB1:12/0/1024",
                    "2.5s S>C NON 4.08 T11 [1, 9]",
                    "2.5s C>S NON PUT T13 QB1:1/1/1024",
                    "2.5s C>S NON PUT T14 QB1:9/1/1024",
                    "6.5s S>C NON 4.08 T12 [10]",
                    "6.5s C>S NON PUT T15 QB1:10/1/1024",
                    "6.5s S>C NON 2.04 T15",
                ),
            wire.lines(),
        )
        assertContentEquals(upload, wire.server.received.single().payload)
        val resent = wire.trace.filter { it.fromClient && it.copy == 2 }.map { it.message.options }
        assertTrue(resent.all { it.size1 == upload.size.toLong() && it.requestTags.single().contentEquals(byteArrayOf(9)) })
    }

    @Test
    fun rfc9177Figure6EventualFailure() {
        val wire = QBlockWire()
        wire.drop = { it.fromClient && it.num == 1L }

        wire.client.upload(body(2 * 1024 + 100))
        wire.runUntil(123.seconds)
        assertEquals(1, wire.server.uploads.size, "the partial body is kept until 124 s")
        wire.runUntil(124.seconds)

        assertEquals(
            listOf("4s S>C NON 4.08 T2 [1]", "12s S>C NON 4.08 T2 [1]", "28s S>C NON 4.08 T2 [1]", "60s S>C NON 4.08 T2 [1]"),
            wire.trace.filter { !it.fromClient }.map { it.toString() },
            "§7.2: NON_RECEIVE_TIMEOUT doubled after every request, all on the token of the highest block",
        )
        assertTrue(wire.server.uploads.isEmpty(), "discarded at 124 s without a fifth 4.08")
        assertEquals(1, wire.server.actions.count { it is QBlockAction.Discard })
        // The client took all four 4.08s on one token and sent block 1 again for each.
        assertEquals(5, wire.client.actions.count { it is QBlockAction.SendBlock<*> && it.num == 1L })
        assertTrue(wire.client.actions.none { it is QBlockAction.Fail })
        wire.runUntil(184.seconds)
        val failure = wire.client.actions.filterIsInstance<QBlockAction.Fail>().single()
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
    }

    // ---------------------------------------------------------------- §10.2: Q-Block2 with NON

    private fun observingWire(): QBlockWire = QBlockWire().apply {
        client.method = CoapCode.GET
        client.observe = 0
    }

    @Test
    fun rfc9177Figure7ObserveSameOnEveryBlock() {
        val wire = observingWire()
        val first = body(3 * 1024 + 300, seed = 1)
        val second = body(3 * 1024 + 300, seed = 2)
        wire.server.responseBody = { first }

        wire.client.download()
        wire.runUntil(10.seconds)
        wire.server.notify(second)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("0s C>S NON GET T0 Obs:0 QB2:0/1/1024") +
                blocks(0..3) { "0s S>C NON 2.05 T0 Obs:1000 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:10" } +
                blocks(0..3) { "10s S>C NON 2.05 T0 Obs:1001 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:11" },
            wire.lines(),
        )
        val (one, two) = wire.client.delivered
        assertContentEquals(first, one.payload)
        assertContentEquals(second, two.payload)
        assertEquals(listOf(1000L, 1001L), listOf(one.options.observe, two.options.observe), "one emission per body")
        assertContentEquals(byteArrayOf(0x10), one.options.etags.single())
        assertContentEquals(byteArrayOf(0x11), two.options.etags.single())
        assertNull(one.options.qBlock2)
        assertNull(one.options.size2)
    }

    @Test
    fun rfc9177Figure8ContinueOnOriginalToken() {
        val wire = observingWire()
        val first = body(10 * 1024 + 10, seed = 1)
        val second = body(10 * 1024 + 10, seed = 2)
        wire.server.responseBody = { first }

        wire.client.download()
        wire.runUntil(10.seconds)
        wire.server.notify(second)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("0s C>S NON GET T0 Obs:0 QB2:0/1/1024") +
                blocks(0..9) { "0s S>C NON 2.05 T0 Obs:1000 QB2:$it/1/1024 ETag:10" } +
                listOf("0s C>S NON GET T1 QB2:10/1/1024", "0s S>C NON 2.05 T0 Obs:1000 QB2:10/0/1024 ETag:10") +
                blocks(0..9) { "10s S>C NON 2.05 T0 Obs:1001 QB2:$it/1/1024 ETag:11" } +
                listOf("10s C>S NON GET T2 QB2:10/1/1024", "10s S>C NON 2.05 T0 Obs:1001 QB2:10/0/1024 ETag:11"),
            wire.lines(),
            "§4.4: the Continue carries no Observe and gets nothing on its own token; the set follows at once",
        )
        assertContentEquals(first, wire.client.delivered[0].payload)
        assertContentEquals(second, wire.client.delivered[1].payload)
    }

    @Test
    fun rfc9177Figure9MissingAfterLaterSet() {
        val wire = observingWire()
        val notification = body(10 * 1024 + 10, seed = 2)
        wire.server.responseBody = { body(1024 + 10, seed = 1) }
        wire.client.download()
        wire.runUntil(10.seconds)
        val start = wire.trace.size
        wire.drop = serverBlockDrops(0x11, mapOf(1L to setOf(1, 2), 9L to setOf(1)))

        wire.server.notify(notification)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("10s S>C NON 2.05 T0 Obs:1001 QB2:0/1/1024 ETag:11", "X 10s S>C NON 2.05 T0 Obs:1001 QB2:1/1/1024 ETag:11") +
                blocks(2..8) { "10s S>C NON 2.05 T0 Obs:1001 QB2:$it/1/1024 ETag:11" } + listOf(
                    "X 10s S>C NON 2.05 T0 Obs:1001 QB2:9/1/1024 ETag:11",
                    "12.5s S>C NON 2.05 T0 Obs:1001 QB2:10/0/1024 ETag:11",
                    "12.5s C>S NON GET T1 QB2:1/0/1024 QB2:9/0/1024",
                    "X 12.5s S>C NON 2.05 T1 QB2:1/1/1024 ETag:11",
                    "12.5s S>C NON 2.05 T1 QB2:9/1/1024 ETag:11",
                    "16.5s C>S NON GET T2 QB2:1/0/1024",
                    "16.5s S>C NON 2.05 T2 QB2:1/1/1024 ETag:11",
                ),
            wire.lines(start),
        )
        assertContentEquals(notification, wire.client.delivered.last().payload)
        assertEquals(1001L, wire.client.delivered.last().options.observe, "Observe of the body, though block 1 came without")
    }

    @Test
    fun rfc9177Figure10MBitRecoveryAndResentSet() {
        val wire = observingWire()
        wire.client.missingBlockMBit = true
        val notification = body(10 * 1024 + 10, seed = 2)
        wire.server.responseBody = { body(1024 + 10, seed = 1) }
        wire.client.download()
        wire.runUntil(10.seconds)
        val start = wire.trace.size
        wire.drop = serverBlockDrops(0x11, (2L..10L).associateWith { setOf(1) })

        wire.server.notify(notification)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("10s S>C NON 2.05 T0 Obs:1001 QB2:0/1/1024 ETag:11", "10s S>C NON 2.05 T0 Obs:1001 QB2:1/1/1024 ETag:11") +
                blocks(2..9) { "X 10s S>C NON 2.05 T0 Obs:1001 QB2:$it/1/1024 ETag:11" } +
                listOf("X 12.5s S>C NON 2.05 T0 Obs:1001 QB2:10/0/1024 ETag:11", "14s C>S NON GET T1 QB2:2/1/1024") +
                blocks(2..9) { "14s S>C NON 2.05 T1 QB2:$it/1/1024 ETag:11" } +
                listOf("14s C>S NON GET T2 QB2:10/1/1024", "14s S>C NON 2.05 T0 Obs:1001 QB2:10/0/1024 ETag:11"),
            wire.lines(start),
            "§4.4: 2/M=1 asks for the rest of set 0; the Continue sends block 10 again on the original token",
        )
        assertContentEquals(notification, wire.client.delivered.last().payload)
    }

    // ---------------------------------------------------------------- §10.3: Q-Block1 and Q-Block2 together

    private fun fetchingWire(): QBlockWire = QBlockWire().apply {
        client.method = CoapCode.FETCH
        client.observe = 0
    }

    @Test
    fun rfc9177Figure11FetchObserveToken() {
        val wire = fetchingWire()
        val query = body(2 * 1024 + 100, seed = 1)
        val first = body(3 * 1024 + 300, seed = 2)
        wire.server.responseBody = { first }

        wire.client.upload(query)
        wire.runUntil(10.seconds)
        wire.server.notify(body(3 * 1024 + 300, seed = 3))
        wire.runUntil(300.seconds)

        assertEquals(
            blocks(0..2) { "0s C>S NON FETCH T$it Obs:0 QB1:$it/${if (it < 2) 1 else 0}/1024" } +
                blocks(0..3) { "0s S>C NON 2.05 T2 Obs:1000 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:10" } +
                blocks(0..3) { "10s S>C NON 2.05 T2 Obs:1001 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:11" },
            wire.lines(),
        )
        assertContentEquals(query, wire.server.received.single().payload)
        assertEquals(0L, wire.server.received.single().options.observe)
        assertEquals(setOf(0, 1), wire.client.released, "the 2.05's token stays as the observation token")
        assertContentEquals(first, wire.client.delivered.first().payload)
        assertEquals(2, wire.client.delivered.size)
    }

    @Test
    fun rfc9177Figure12ContinueWithoutRequestTag() {
        val wire = fetchingWire()
        wire.client.followUpRequestTag = false
        val query = body(10 * 1024 + 10, seed = 1)
        val first = body(10 * 1024 + 20, seed = 2)
        wire.server.responseBody = { first }

        wire.client.upload(query)
        wire.runUntil(10.seconds)
        wire.server.notify(body(10 * 1024 + 20, seed = 3))
        wire.runUntil(300.seconds)

        assertEquals(
            blocks(0..9) { "0s C>S NON FETCH T$it Obs:0 QB1:$it/1/1024" } + listOf(
                "0s S>C NON 2.31 T9 QB1:9/1/1024",
                "0s C>S NON FETCH T10 Obs:0 QB1:10/0/1024",
            ) + blocks(0..9) { "0s S>C NON 2.05 T10 Obs:1000 QB2:$it/1/1024 ETag:10" } + listOf(
                "0s C>S NON FETCH T11 QB2:10/1/1024",
                "0s S>C NON 2.05 T10 Obs:1000 QB2:10/0/1024 ETag:10",
            ) + blocks(0..9) { "10s S>C NON 2.05 T10 Obs:1001 QB2:$it/1/1024 ETag:11" } + listOf(
                "10s C>S NON FETCH T12 QB2:10/1/1024",
                "10s S>C NON 2.05 T10 Obs:1001 QB2:10/0/1024 ETag:11",
            ),
            wire.lines(),
        )
        val continueRequest = wire.trace.single { it.fromClient && it.message.options.qBlock2 != null && it.at == 0.seconds }.message
        assertTrue(continueRequest.options.requestTags.isEmpty() && continueRequest.payloadSize == 0)
        assertNull(continueRequest.options.qBlock1)
        assertContentEquals(first, wire.client.delivered.first().payload)
    }

    /**
     * Figures 13, 14 and 15. Figure 14 sends the response body on T:c3, the token of the block that ended
     * the body; kcoap answers on the request that completed it, T5 here (Figure 5 does the same), which §4.3
     * equally allows, and the client takes either ([QBlock1SenderTest]).
     */
    @Test
    fun rfc9177Figure13To15LossBothWays() {
        val wire = fetchingWire()
        val query = body(3 * 1024 + 50, seed = 1)
        val first = body(3 * 1024 + 300, seed = 2)
        val second = body(2 * 1024 + 10, seed = 3)
        wire.server.responseBody = { first }
        val serverDrops = serverBlockDrops(0x10, mapOf(1L to setOf(1, 2), 3L to setOf(1)))
        val notificationDrops = serverBlockDrops(0x11, mapOf(1L to setOf(1)))
        wire.drop = { line ->
            (line.fromClient && line.message.options.qBlock1?.number in setOf(1L, 2L) && line.copy == 1) ||
                serverDrops(line) || notificationDrops(line)
        }

        wire.client.upload(query)
        wire.runUntil(20.seconds)
        wire.server.notify(second)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf(
                "0s C>S NON FETCH T0 Obs:0 QB1:0/1/1024",
                "X 0s C>S NON FETCH T1 Obs:0 QB1:1/1/1024",
                "X 0s C>S NON FETCH T2 Obs:0 QB1:2/1/1024",
                "0s C>S NON FETCH T3 Obs:0 QB1:3/0/1024",
                "4s S>C NON 4.08 T3 [1, 2]",
                "4s C>S NON FETCH T4 Obs:0 QB1:1/1/1024",
                "4s C>S NON FETCH T5 Obs:0 QB1:2/1/1024",
                "4s S>C NON 2.05 T5 Obs:1000 QB2:0/1/1024 ETag:10",
                "X 4s S>C NON 2.05 T5 Obs:1000 QB2:1/1/1024 ETag:10",
                "4s S>C NON 2.05 T5 Obs:1000 QB2:2/1/1024 ETag:10",
                "X 4s S>C NON 2.05 T5 Obs:1000 QB2:3/0/1024 ETag:10",
                "8s C>S NON FETCH T6 QB2:1/0/1024 QB2:3/0/1024",
                "X 8s S>C NON 2.05 T6 QB2:1/1/1024 ETag:10",
                "8s S>C NON 2.05 T6 QB2:3/0/1024 ETag:10",
                "12s C>S NON FETCH T7 QB2:1/0/1024",
                "12s S>C NON 2.05 T7 QB2:1/1/1024 ETag:10",
                "20s S>C NON 2.05 T5 Obs:1001 QB2:0/1/1024 ETag:11",
                "X 20s S>C NON 2.05 T5 Obs:1001 QB2:1/1/1024 ETag:11",
                "20s S>C NON 2.05 T5 Obs:1001 QB2:2/0/1024 ETag:11",
                "24s C>S NON FETCH T8 QB2:1/0/1024",
                "24s S>C NON 2.05 T8 QB2:1/1/1024 ETag:11",
            ),
            wire.lines(),
            "the lost last block 3 is known from Size2 and asked for with block 1",
        )
        assertContentEquals(query, wire.server.received.single().payload)
        assertContentEquals(first, wire.client.delivered[0].payload)
        assertContentEquals(second, wire.client.delivered[1].payload)
        // §4.7, Figure 15: follow-ups repeat the method and Request-Tag, without Q-Block1 and payload.
        val followUps = wire.trace.filter { it.fromClient && it.message.options.qBlock2 != null }.map { it.message }
        assertTrue(followUps.all { it.code == CoapCode.FETCH && it.options.requestTags.isNotEmpty() && it.payloadSize == 0 })
        assertTrue(followUps.all { it.options.qBlock1 == null && it.options.observe == null })
        // Figure 15 gives two messages Message ID 0xa7; every message here has its own.
        val serverIds = wire.trace.filter { !it.fromClient }.map { it.message.messageId }
        assertEquals(serverIds.size, serverIds.toSet().size)
    }

    // ---------------------------------------------------------------- Appendix A: Confirmable

    @Test
    fun rfc9177Figure16ConQBlock1InLockStep() {
        val wire = QBlockWire()
        val upload = body(3 * 1024 + 500)

        wire.client.upload(upload, confirmable = true)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf(
                "0s C>S CON PUT T0 QB1:0/1/1024", "0s S>C ACK 0.00",
                "0s C>S CON PUT T1 QB1:1/1/1024", "0s S>C ACK 0.00",
                "0s C>S CON PUT T2 QB1:2/1/1024", "0s S>C ACK 0.00",
                "0s C>S CON PUT T3 QB1:3/0/1024", "0s S>C ACK 2.04 T3",
            ),
            wire.lines(),
            "NSTART 1: each block waits for the previous ACK; no 2.31 for Confirmable blocks",
        )
        assertContentEquals(upload, wire.server.received.single().payload)
    }

    /** Figure 16 as drawn, with NSTART 3: three blocks before the first ACK, the fourth on that ACK. */
    @Test
    fun rfc9177Figure16ConQBlock1WithNstart3() {
        val wire = QBlockWire(TransmissionParameters(nstart = 3))
        val upload = body(3 * 1024 + 500)

        wire.client.upload(upload, confirmable = true)
        wire.runUntil(300.seconds)

        assertEquals(
            listOf(
                "0s C>S CON PUT T0 QB1:0/1/1024", "0s C>S CON PUT T1 QB1:1/1/1024", "0s C>S CON PUT T2 QB1:2/1/1024",
                "0s S>C ACK 0.00", "0s S>C ACK 0.00", "0s S>C ACK 0.00",
                "0s C>S CON PUT T3 QB1:3/0/1024", "0s S>C ACK 2.04 T3",
            ),
            wire.lines(),
        )
        assertContentEquals(upload, wire.server.received.single().payload)
    }

    @Test
    fun rfc9177Figure17ConQBlock1RetransmitsThenFails() {
        val wire = QBlockWire(TransmissionParameters(nstart = 3))
        wire.drop = { it.fromClient && ((it.num == 1L && it.copy == 1) || it.num == 2L) }

        wire.client.upload(body(3 * 1024 + 500), confirmable = true)
        wire.runUntil(61.seconds)
        assertTrue(wire.client.actions.none { it is QBlockAction.Fail })
        wire.runUntil(62.seconds)

        val block1 = wire.trace.filter { it.fromClient && it.num == 1L }
        assertEquals(listOf("X 0s C>S CON PUT T1 QB1:1/1/1024", "2s C>S CON PUT T1 QB1:1/1/1024"), block1.map { it.toString() })
        assertEquals(1, block1.map { it.message.messageId }.toSet().size, "the same Message ID and token")
        val block2 = wire.trace.filter { it.fromClient && it.num == 2L }
        assertEquals(listOf(0, 2, 6, 14, 30).map { it.seconds }, block2.map { it.at }, "RFC 7252 §4.2 back-off, MAX_RETRANSMIT 4")
        assertEquals(1, block2.map { it.message.messageId }.toSet().size)
        val failure = wire.client.actions.filterIsInstance<QBlockAction.Fail>().single()
        assertEquals(CoapException.Reason.TRANSMISSION_TIMEOUT, failure.reason)
        assertTrue(wire.server.received.isEmpty())
    }

    @Test
    fun rfc9177Figure18ConQBlock2() {
        val wire = QBlockWire(TransmissionParameters(nstart = 3))
        wire.client.method = CoapCode.GET
        wire.client.observe = 0
        val bodies = (1..3).map { body(3 * 1024 + 300, seed = it) }
        wire.server.responseBody = { bodies[0] }
        wire.drop = serverBlockDrops(0x12, mapOf(1L to setOf(1), 2L to setOf(1, 2)))

        wire.client.download(confirmable = true)
        wire.runUntil(10.seconds)
        wire.server.notify(bodies[1])
        wire.runUntil(20.seconds)
        wire.server.notify(bodies[2])
        wire.runUntil(300.seconds)

        val ack = "C>S ACK 0.00"
        assertEquals(
            listOf("0s C>S CON GET T0 Obs:0 QB2:0/1/1024", "0s S>C ACK 2.05 T0 Obs:1000 QB2:0/1/1024 ETag:10") +
                blocks(1..3) { "0s S>C CON 2.05 T0 Obs:1000 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:10" } +
                List(3) { "0s $ack" } +
                blocks(0..2) { "10s S>C CON 2.05 T0 Obs:1001 QB2:$it/1/1024 ETag:11" } + List(3) { "10s $ack" } +
                listOf("10s S>C CON 2.05 T0 Obs:1001 QB2:3/0/1024 ETag:11", "10s $ack") + listOf(
                    "20s S>C CON 2.05 T0 Obs:1002 QB2:0/1/1024 ETag:12",
                    "X 20s S>C CON 2.05 T0 Obs:1002 QB2:1/1/1024 ETag:12",
                    "X 20s S>C CON 2.05 T0 Obs:1002 QB2:2/1/1024 ETag:12",
                    "20s $ack",
                    "20s S>C CON 2.05 T0 Obs:1002 QB2:3/0/1024 ETag:12",
                    "20s $ack",
                    "22s S>C CON 2.05 T0 Obs:1002 QB2:1/1/1024 ETag:12",
                    "X 22s S>C CON 2.05 T0 Obs:1002 QB2:2/1/1024 ETag:12",
                    "22s $ack",
                    "26s S>C CON 2.05 T0 Obs:1002 QB2:2/1/1024 ETag:12",
                    "26s $ack",
                ),
            wire.lines(),
            "block 0 piggybacked, Observe on every block, lost blocks retransmitted by the messaging layer",
        )
        assertEquals(bodies.map { it.toList() }, wire.client.delivered.map { it.payload.toList() })
    }

    // ---------------------------------------------------------------- Appendix B: reliable transports

    @Test
    fun rfc9177Figure19ReliableQBlock1() {
        val wire = QBlockWire(reliable = true)
        val upload = body(3 * 1024 + 500)

        wire.client.upload(upload)
        wire.runUntil(300.seconds)

        assertEquals(
            blocks(0..3) { "0s C>S PUT T$it QB1:$it/${if (it < 3) 1 else 0}/1024" } + "0s S>C 2.04 T3",
            wire.lines(),
        )
        assertContentEquals(upload, wire.server.received.single().payload)
        // Each intermediate request counts as answered, so a Custody Pong is not held up behind it.
        assertEquals(listOf(0, 1, 2), wire.server.actions.filterIsInstance<QBlockAction.NoResponse<QBlockWire.Ref>>().map { it.ref.token })
        assertTrue(wire.server.actions.none { it is QBlockAction.Send231<*> || it is QBlockAction.Send408<*> })
    }

    @Test
    fun rfc9177Figure20ReliableQBlock2() {
        val wire = QBlockWire(reliable = true)
        wire.client.method = CoapCode.GET
        wire.client.observe = 0
        val first = body(3 * 1024 + 300, seed = 1)
        wire.server.responseBody = { first }

        wire.client.download()
        wire.runUntil(10.seconds)
        wire.server.notify(body(3 * 1024 + 300, seed = 2))
        wire.runUntil(300.seconds)

        assertEquals(
            listOf("0s C>S GET T0 Obs:0 QB2:0/1/1024") +
                blocks(0..3) { "0s S>C 2.05 T0 Obs:1000 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:10" } +
                blocks(0..3) { "10s S>C 2.05 T0 Obs:1001 QB2:$it/${if (it < 3) 1 else 0}/1024 ETag:11" },
            wire.lines(),
            "no Continue and no timers on a reliable transport",
        )
        assertContentEquals(first, wire.client.delivered.first().payload)
        assertEquals(2, wire.client.delivered.size)
        assertFalse(wire.trace.any { it.message.type == CoapMessageType.NON })
        assertTrue(wire.trace.none { it.message.options.contentFormat != null && CoapOptions.Q_BLOCK1.number in it.message.options })
    }
}

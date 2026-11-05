package dev.kcoap.coap.stack

import dev.kcoap.cbor.Cbor
import dev.kcoap.cbor.CborInt
import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.stack.QBlockMessages.block
import dev.kcoap.coap.stack.QBlockMessages.upload
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The building blocks of Q-Block (RFC 9177): codec, structural checks, options, parameters. */
class QBlockPartsTest {
    // ---------------------------------------------------------------- §5: application/missing-blocks+cbor-seq

    @Test
    fun rfc9177Section5MissingBlocksDecodeAscendingNumbers() {
        assertEquals(listOf(1L, 9L), MissingBlocks.decode("0109".hexToBytes()))
        assertEquals(listOf(1L, 9L), MissingBlocks.decode("010109".hexToBytes()), "duplicates are ignored")
        assertEquals(listOf(46L, 300L, 70000L), MissingBlocks.decode("182e19012c1a00011170".hexToBytes()))
        assertEquals(listOf(5L), MissingBlocks.decode("1b0000000000000005".hexToBytes()), "any valid head length")
        assertEquals(listOf(0xFFFFFL), MissingBlocks.decode("1a000fffff".hexToBytes()))
    }

    @Test
    fun rfc9177Section5MissingBlocksListsThatMustBeDroppedDecodeToNull() {
        assertNull(MissingBlocks.decode("0901".hexToBytes()), "not ascending")
        assertNull(MissingBlocks.decode(ByteArray(0)), "empty")
        assertNull(MissingBlocks.decode("0141".hexToBytes()), "a byte string is not an unsigned integer")
        assertNull(MissingBlocks.decode("20".hexToBytes()), "a negative integer is not one either")
        assertNull(MissingBlocks.decode("18".hexToBytes()), "truncated")
        assertNull(MissingBlocks.decode("1901".hexToBytes()), "truncated")
        assertNull(MissingBlocks.decode("1c".hexToBytes()), "reserved additional information")
        assertNull(MissingBlocks.decode("1f".hexToBytes()), "indefinite length")
        assertNull(MissingBlocks.decode("1a00100000".hexToBytes()), "above the 20-bit block number")
        assertNull(MissingBlocks.decode("1b0100000000000000".hexToBytes()), "far above it")
    }

    @Test
    fun rfc9177Section5MissingBlocksEncodeInTheShortestForm() {
        assertEquals("182e", MissingBlocks.encode(listOf(46L)).toHex())
        assertEquals("0109", MissingBlocks.encode(listOf(1L, 9L)).toHex())
        assertEquals("0017181818ff19010019ffff1a00010000", MissingBlocks.encode(listOf(0L, 23L, 24L, 0xFFL, 0x100L, 0xFFFFL, 0x10000L)).toHex())
        assertEquals("", MissingBlocks.encode(emptyList()).toHex())
    }

    @Test
    fun rfc9177Section5MissingBlocksEncodingRefusesWhatIsNotAList() {
        assertFailsWith<IllegalArgumentException> { MissingBlocks.encode(listOf(9L, 1L)) }
        assertFailsWith<IllegalArgumentException> { MissingBlocks.encode(listOf(1L, 1L)) }
        assertFailsWith<IllegalArgumentException> { MissingBlocks.encode(listOf(-1L)) }
        assertFailsWith<IllegalArgumentException> { MissingBlocks.encode(listOf(0x100000L)) }
    }

    @Test
    fun rfc8742MissingBlocksAgreeWithKcoapCbor() {
        val random = Random(9177)
        repeat(50) {
            val numbers = List(random.nextInt(1, 12)) { random.nextLong(0, 0x100000) }.distinct().sorted()
            val encoded = MissingBlocks.encode(numbers)
            assertEquals(encoded.toHex(), Cbor.encodeSequence(numbers.map { CborInt(it) }).toHex())
            assertEquals(numbers, Cbor.decodeSequence(encoded).map { (it as CborInt).value })
            assertEquals(numbers, MissingBlocks.decode(encoded))
        }
    }

    // ---------------------------------------------------------------- structural checks, rows 6 to 12 and 39

    private fun check(message: CoapMessage, allowsBert: Boolean = false, maxBodySize: Int = 1 shl 20): QBlockViolation? =
        QBlockCheck.request(message, allowsBert, maxBodySize)

    private fun get(vararg options: CoapOption): CoapMessage = CoapMessage.request(CoapCode.GET) {
        options { options.forEach { add(it) } }
    }

    private fun qBlock2(num: Long, more: Boolean, size: Int = 64): CoapOption =
        CoapOption.uint(CoapOptions.Q_BLOCK2.number, block(num, more, size).toUint())

    @Test
    fun rfc9177Section4_1RequestsWithoutQBlockAndValidOnesPass() {
        assertNull(check(get()))
        assertNull(check(upload(0, 3000)))
        assertNull(check(upload(46, 3000)), "the last block ends at Size1")
        assertNull(check(get(qBlock2(0, true))))
        assertNull(check(get(qBlock2(2, false), qBlock2(7, true))), "an ascending missing-block list")
        assertNull(check(upload(0, 3000) { qBlock2(block(0, true)) }), "Q-Block1 with one Q-Block2 for the response")
        assertNull(check(upload(0, 0)), "an empty body is one empty block")
    }

    @Test
    fun rfc9177Section4_1RepeatingQBlock1Gets402() {
        val repeated = upload(0, 3000) { add(CoapOption.uint(CoapOptions.Q_BLOCK1.number, block(1, true).toUint())) }
        assertEquals(CoapCode.BAD_OPTION, check(repeated)?.code)
    }

    @Test
    fun rfc9177Section4_1MixingQBlockWithBlockGets402() {
        assertEquals(CoapCode.BAD_OPTION, check(upload(0, 3000) { block1(block(0, true)) })?.code)
        assertEquals(CoapCode.BAD_OPTION, check(get(qBlock2(0, true)).toBuilder().options { block2(block(0, true)) }.build())?.code)
    }

    @Test
    fun rfc9177Section4_2AnOptionOfMoreThanThreeBytesGets402() {
        val long = get(CoapOption(CoapOptions.Q_BLOCK2.number, byteArrayOf(0, 0, 0, 0x16)))
        assertEquals("A Q-Block option longer than 3 bytes", check(long)?.diagnostic)
        assertEquals(CoapCode.BAD_OPTION, check(long)?.code)
    }

    @Test
    fun rfc9177Section4_2Szx7IsReservedWithoutBert() {
        val bert = get(CoapOption.uint(CoapOptions.Q_BLOCK2.number, BlockOption(0, true, 7).toUint()))
        assertEquals(CoapCode.BAD_REQUEST, check(bert)?.code)
        assertNull(check(bert, allowsBert = true), "RFC 8323 §6: both CSMs indicated BERT")
        val bertUpload = CoapMessage.request(CoapCode.PUT) {
            payload = ByteArray(2048)
            options {
                requestTag(byteArrayOf(1))
                size1(3000)
                qBlock1(BlockOption(0, true, 7))
            }
        }
        assertNull(check(bertUpload, allowsBert = true), "a BERT block of two 1024-byte units")
        val ragged = bertUpload.toBuilder().apply { payload = ByteArray(1500) }.build()
        assertEquals(CoapCode.BAD_REQUEST, check(ragged, allowsBert = true)?.code, "not a multiple of 1024")
    }

    @Test
    fun rfc7959Section2_3AFullBlockIsRequiredWhileMIsSet() {
        val short = upload(0, 3000).toBuilder().apply { payload = ByteArray(10) }.build()
        assertEquals(CoapCode.BAD_REQUEST, check(short)?.code)
    }

    @Test
    fun rfc9177Section4_3QBlock1WithoutRequestTagOrSize1Gets400() {
        val noTag = upload(0, 3000).toBuilder().options { remove(CoapOptions.REQUEST_TAG.number) }.build()
        assertEquals("Q-Block1 without Request-Tag", check(noTag)?.diagnostic)
        val noSize = upload(0, 3000).toBuilder().options { remove(CoapOptions.SIZE1.number) }.build()
        assertEquals("Q-Block1 without Size1", check(noSize)?.diagnostic)
    }

    @Test
    fun rfc9177Section4_6BlocksMustFitSize1() {
        val beyond = upload(0, 3000).toBuilder().options { qBlock1(block(47, true)) }.build()
        assertEquals(CoapCode.BAD_REQUEST, check(beyond)?.code)
        val wrongEnd = upload(46, 3000).toBuilder().options { size1(4000) }.build()
        assertEquals(CoapCode.BAD_REQUEST, check(wrongEnd)?.code, "a last block must end at Size1")
        val tooLarge = check(upload(0, 3000), maxBodySize = 2048)
        assertEquals(CoapCode.REQUEST_ENTITY_TOO_LARGE, tooLarge?.code)
        assertEquals(2048L, tooLarge?.size1, "RFC 7959 §2.9.3: Size1 tells the largest acceptable body")
        assertEquals("4.13 Request Entity Too Large Body of 3000 bytes exceeds 2048", tooLarge.toString())
    }

    @Test
    fun rfc9177Section4_4MissingBlockListsAreStrictlyAscendingInOneSize() {
        assertEquals(CoapCode.BAD_REQUEST, check(get(qBlock2(7, false), qBlock2(2, false)))?.code)
        assertEquals(CoapCode.BAD_REQUEST, check(get(qBlock2(2, false), qBlock2(2, false)))?.code)
        assertEquals(CoapCode.BAD_REQUEST, check(get(qBlock2(2, false), qBlock2(7, false, 128)))?.code)
        val withQBlock1 = upload(0, 3000) {
            addQBlock2(block(1, false))
            addQBlock2(block(2, false))
        }
        assertEquals("Missing blocks are asked for without Q-Block1", check(withQBlock1)?.diagnostic)
    }

    @Test
    fun rfc7252Section5_4_1ResponsesWithBadQBlockOptionsAreRejected() {
        fun response(block: OptionSet.Builder.() -> Unit) = CoapMessage.build {
            code = CoapCode.CONTENT
            options(block)
        }
        assertNull(QBlockCheck.response(response {}, allowsBert = false))
        assertNull(QBlockCheck.response(response { qBlock2(block(0, true)) }, allowsBert = false))
        assertEquals(
            "A Q-Block option longer than 3 bytes",
            QBlockCheck.response(response { add(CoapOptions.Q_BLOCK1.number, ByteArray(4)) }, allowsBert = false),
        )
        assertEquals("A Q-Block option repeated in a response", QBlockCheck.response(response {
            addQBlock2(block(0, true))
            addQBlock2(block(1, true))
        }, allowsBert = false))
        assertEquals("Q-Block mixed with Block1 or Block2", QBlockCheck.response(response {
            qBlock2(block(0, true))
            block2(block(0, true))
        }, allowsBert = false))
        val bert = response { qBlock2(BlockOption(0, true, 7)) }
        assertEquals("SZX 7 is reserved without BERT", QBlockCheck.response(bert, allowsBert = false))
        assertNull(QBlockCheck.response(bert, allowsBert = true))
    }

    // ---------------------------------------------------------------- the options and the operation key

    @Test
    fun rfc9177Section4_2OptionSetReadsAndWritesQBlockOptions() {
        val options = OptionSet.build {
            qBlock1(block(3, true))
            qBlock1(block(4, false))
            qBlock2(block(9, true))
            qBlock2(block(1, false))
            addQBlock2(block(5, true))
        }
        assertEquals(block(4, false), options.qBlock1, "set replaces")
        assertEquals(block(1, false), options.qBlock2)
        assertEquals(listOf(block(1, false), block(5, true)), options.qBlock2List)
        assertNull(OptionSet.EMPTY.qBlock1)
        assertNull(OptionSet.EMPTY.qBlock2)
        assertEquals(emptyList(), OptionSet.EMPTY.qBlock2List)
        assertEquals(272, ContentFormat.MISSING_BLOCKS_CBOR_SEQ)
    }

    @Test
    fun rfc9177Section4_3QBlockOptionsAreNotPartOfTheOperationKey() {
        val first = upload(0, 3000).options
        val other = upload(5, 3000) { addQBlock2(block(0, true)) }.options
        assertEquals(Blockwise.operationOptions(first), Blockwise.operationOptions(other))
        assertTrue(Blockwise.operationOptions(first).any { it.number == CoapOptions.REQUEST_TAG.number })
    }

    // ---------------------------------------------------------------- §7.2 parameters

    @Test
    fun rfc9177Section7_2DefaultsOfTable3AndTable4() {
        val parameters = TransmissionParameters()
        assertEquals(10, parameters.maxPayloads)
        assertEquals(2.seconds, parameters.nonTimeout)
        assertEquals(4.seconds, parameters.nonReceiveTimeout)
        assertEquals(4, parameters.nonMaxRetransmit)
        assertEquals(247.seconds, parameters.nonPartialTimeout)
        assertTrue("MAX_PAYLOADS=10, NON_TIMEOUT=2s, NON_RECEIVE_TIMEOUT=4s, NON_MAX_RETRANSMIT=4" in parameters.toString())
    }

    @Test
    fun rfc9177Section7_2NonReceiveTimeoutMustOutlastNonTimeoutRandom() {
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(nonReceiveTimeout = 3.9.seconds) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(maxPayloads = 0) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(nonTimeout = 0.seconds) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(nonMaxRetransmit = 21) }
        assertFailsWith<IllegalArgumentException> { TransmissionParameters(nonMaxRetransmit = -1) }
        // The default follows the bound where 2 × NON_TIMEOUT would break it.
        assertEquals(1.75.seconds, TransmissionParameters(ackTimeout = 500.milliseconds).nonReceiveTimeout)
        assertEquals(5.seconds, TransmissionParameters(ackRandomFactor = 2.0).nonReceiveTimeout)
        assertEquals(6.seconds, TransmissionParameters(ackTimeout = 3.seconds).nonReceiveTimeout)
    }}
}

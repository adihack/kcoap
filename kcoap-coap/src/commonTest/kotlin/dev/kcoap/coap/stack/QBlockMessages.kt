package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.OptionSet

/** Messages for the Q-Block state machine tests. */
internal object QBlockMessages {
    fun block(num: Long, more: Boolean, size: Int = 64): BlockOption = BlockOption(num, more, BlockOption.sizeExponentFor(size))

    /** Block [num] of a Q-Block1 PUT of a [size1]-byte body in blocks of [size] bytes. */
    fun upload(
        num: Long,
        size1: Int,
        size: Int = 64,
        type: CoapMessageType = CoapMessageType.NON,
        tag: ByteArray = byteArrayOf(7),
        options: OptionSet.Builder.() -> Unit = {},
    ): CoapMessage {
        val offset = (num * size).toInt()
        val end = minOf(size1, offset + size)
        return CoapMessage.request(CoapCode.PUT) {
            this.type = type
            token = byteArrayOf(num.toByte())
            payload = ByteArray(maxOf(0, end - offset)) { (offset + it).toByte() }
            options {
                uriPath("r")
                requestTag(tag)
                size1(size1.toLong())
                qBlock1(block(num, end < size1, size))
                options()
            }
        }
    }

    /** Block [num] of a Q-Block2 2.05 body of [size2] bytes in blocks of [size] bytes. */
    fun download(
        num: Long,
        size2: Int,
        size: Int = 64,
        etag: Int = 1,
        options: OptionSet.Builder.() -> Unit = {},
    ): CoapMessage {
        val offset = (num * size).toInt()
        val end = minOf(size2, offset + size)
        return CoapMessage.build {
            type = CoapMessageType.NON
            code = CoapCode.CONTENT
            token = byteArrayOf(1)
            payload = ByteArray(maxOf(0, end - offset)) { (offset + it).toByte() }
            options {
                etag(byteArrayOf(etag.toByte()))
                size2(size2.toLong())
                qBlock2(block(num, end < size2, size))
                options()
            }
        }
    }

    /** A response with [code] and [options]. */
    fun response(code: CoapCode, payload: ByteArray = ByteArray(0), options: OptionSet.Builder.() -> Unit = {}): CoapMessage =
        CoapMessage.build {
            type = CoapMessageType.NON
            this.code = code
            token = byteArrayOf(1)
            this.payload = payload
            options(options)
        }

    /** A 4.08 listing [missing] in Content-Format 272. */
    fun missing(vararg missing: Long): CoapMessage = response(CoapCode.REQUEST_ENTITY_INCOMPLETE, MissingBlocks.encode(missing.toList())) {
        contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ)
    }

    /** A follow-up GET with the Q-Block2 options [blocks]. */
    fun followUp(vararg blocks: BlockOption): CoapMessage = CoapMessage.request(CoapCode.GET) {
        type = CoapMessageType.NON
        token = byteArrayOf(2)
        options { blocks.forEach { addQBlock2(it) } }
    }

    /** The whole body of [size] bytes as [upload] and [download] cut it. */
    fun bytes(size: Int): ByteArray = ByteArray(size) { it.toByte() }
}

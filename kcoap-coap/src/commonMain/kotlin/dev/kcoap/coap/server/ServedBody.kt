package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.QBlock2Sender
import dev.kcoap.transport.Endpoint

/**
 * A response body the server sends with Q-Block2 (RFC 9177 §4.4) and keeps for requests of its missing blocks
 * and Continues: the [response] cut into blocks of [QBlock2Sender.blockSize] bytes, the [sender] that decides
 * which block goes on which request and when, the operation it answers ([key]) and the timer that sends its
 * later sets. Guarded by [QBlockServer]'s lock.
 *
 * Every block carries the same [etag], unique for this transmitted body (§4.4), and Size2 with the whole
 * body's size (§4.6); the response's own ETag, Observe and block-wise options are left out.
 */
internal class ServedBody(
    val key: OperationKey,
    val response: CoapResponse,
    etag: ByteArray,
    val sender: QBlock2Sender<QBlockRequest>,
) : TimedBody() {
    private val etagBytes = etag.copyOf()
    private val body = response.payload
    private val options = response.options.toBuilder().apply { STRIPPED.forEach { remove(it) } }.build()

    val peer: Endpoint get() = key.peer

    val etag: ByteArray get() = etagBytes.copyOf()

    /** Block [num] with M = [more] as a response with [token]; the type and Message ID are left to messaging. */
    fun block(num: Long, more: Boolean, token: ByteArray): CoapMessage {
        val size = sender.blockSize
        val start = minOf(body.size.toLong(), num * size).toInt()
        val end = minOf(body.size, start + size)
        val blockOptions = options.toBuilder().apply {
            etag(etagBytes)
            size2(body.size.toLong())
            qBlock2(BlockOption(num, more, sender.sizeExponent))
        }.build()
        return CoapResponse(response.code, blockOptions, body.copyOfRange(start, end)).toMessage(token)
    }

    override fun toString(): String = "ServedBody($peer, ${response.code}, $sender)"

    companion object {
        /** What a block never repeats from the response: the body's own ETag, Observe and every size and block option. */
        private val STRIPPED = listOf(
            CoapOptions.ETAG, CoapOptions.OBSERVE, CoapOptions.BLOCK1, CoapOptions.BLOCK2, CoapOptions.Q_BLOCK1,
            CoapOptions.Q_BLOCK2, CoapOptions.SIZE1, CoapOptions.SIZE2,
        ).map { it.number }
    }
}

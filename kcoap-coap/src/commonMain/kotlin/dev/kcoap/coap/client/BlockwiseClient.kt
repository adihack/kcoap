package dev.kcoap.coap.client

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.common.ByteArrayBuilder
import dev.kcoap.common.minimalUnsignedBytes
import dev.kcoap.common.toHex
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The client side of RFC 7959: uploads large request bodies in Block1 blocks and downloads large
 * response bodies in Block2 blocks, each block one exchange of its own (above message security, so each
 * block is protected separately, RFC 8613 §4.1.3.4.1).
 */
internal class BlockwiseClient(private val client: CoapClient) {
    private val config = client.config
    private val tagLock = Mutex()

    /** Request-Tags (hex, "" for the absent option) of the Block1 operations in progress, per operation. */
    private val tagsInUse = HashMap<OperationKey, MutableSet<String>>()

    /** Sends [request] (code, options, payload) and returns the whole response. */
    suspend fun send(peer: Endpoint, request: CoapMessage, confirmable: Boolean): CoapResponse {
        val reserve = client.securityReserve
        val maxDatagram = client.endpoint.maxDatagramSize
        // §2.4 early negotiation: propose the configured block size for the response.
        val early = config.blockSize?.takeIf { request.payloadSize == 0 }
        val single = if (early != null) {
            request.toBuilder().options { block2(Blockwise.block(0, false, early)) }.build()
        } else {
            request
        }
        val needsBlock1 = request.payloadSize > 0 &&
            (!Blockwise.fits(single, maxDatagram, reserve) || request.payloadSize > (config.blockSize ?: Int.MAX_VALUE))
        if (!needsBlock1) {
            val response = client.exchange(peer, single, confirmable)
            // RFC 7959 §2.9.3: 4.13 to a request without Block1 is a hint to try Block1.
            if (response.code == CoapCode.REQUEST_ENTITY_TOO_LARGE && request.payloadSize > Blockwise.MIN_BLOCK_SIZE) {
                return upload(peer, request, confirmable, response.options.block1?.size)
            }
            return complete(peer, single, response, confirmable)
        }
        return upload(peer, request, confirmable, config.blockSize)
    }

    /** RFC 7959 §2.5: the body in Block1 blocks, in order, adapting to the server's preferred size. */
    private suspend fun upload(peer: Endpoint, request: CoapMessage, confirmable: Boolean, preferredSize: Int?): CoapResponse {
        val body = request.payload
        val key = OperationKey(peer, null, request.code.value, Blockwise.operationOptions(request.options))
        val tag = acquireTag(key)
        try {
            val base = request.toBuilder().apply {
                payload = ByteArray(0)
                options { tag?.let { requestTag(it) } }
            }.build()
            val header = base.toBuilder().options {
                block1(BlockOption(BlockOption.MAX_NUMBER, true, 6))
                size1(body.size.toLong())
            }.build()
            var size = Blockwise.fittingBlockSize(
                client.endpoint.maxDatagramSize, header, client.securityReserve, preferredSize ?: Blockwise.MAX_BLOCK_SIZE,
            ) ?: throw CoapException("Request options leave no room for a block", CoapException.Reason.MESSAGE_TOO_LARGE)
            var offset = 0
            var restarted = false
            while (true) {
                val number = (offset / size).toLong()
                val end = minOf(body.size, offset + size)
                val more = end < body.size
                val block = base.toBuilder().apply {
                    payload = body.copyOfRange(offset, end)
                    options {
                        block1(Blockwise.block(number, more, size))
                        if (number == 0L) size1(body.size.toLong())
                        if (!more && config.blockSize != null) block2(Blockwise.block(0, false, config.blockSize))
                    }
                }.build()
                val response = client.exchange(peer, block, confirmable)
                val control = response.options.block1
                if (response.code == CoapCode.REQUEST_ENTITY_TOO_LARGE && control != null && control.size < size && !restarted) {
                    // §2.9.3: 4.13 with a smaller SZX is a hint to start over with that size.
                    size = control.size
                    offset = 0
                    restarted = true
                    continue
                }
                if (!more) return complete(peer, base, response, confirmable)
                val continues = response.code == CoapCode.CONTINUE || (response.code.isSuccess && control?.more == false)
                if (!continues) return CoapResponse.of(response)
                // §2.3: use the server's preferred size from now on; the block number scales with it.
                if (control != null && control.size < size && !control.isBert) size = control.size
                offset = end
            }
        } finally {
            releaseTag(key, tag)
        }
    }

    /**
     * Completes a response that may be the first block of a Block2 transfer (RFC 7959 §2.4): fetches the
     * remaining blocks with the same request options and checks that their ETags match. [request] is the
     * request whose options the follow-ups repeat, without Block1 and payload for those after a Block1
     * upload (§2.7). A GET or FETCH whose representation changed midway starts over once.
     */
    suspend fun complete(peer: Endpoint, request: CoapMessage, first: CoapMessage, confirmable: Boolean): CoapResponse {
        val firstBlock = first.options.block2
        if (firstBlock == null || !firstBlock.more) return withoutBlockOptions(first, first.payload)
        val canRestart = request.code == CoapCode.GET || request.code == CoapCode.FETCH
        var restarts = 0
        var head = first
        while (true) {
            val result = download(peer, request, head, confirmable)
            if (result != null) return result
            if (!canRestart || restarts++ >= MAX_RESTARTS) {
                throw CoapException("The representation changed during a Block2 transfer", CoapException.Reason.BLOCKWISE_FAILED)
            }
            head = client.exchange(peer, followUp(request, 0, head.options.block2?.size ?: Blockwise.MAX_BLOCK_SIZE), confirmable)
            if (head.options.block2?.more != true) return withoutBlockOptions(head, head.payload)
        }
    }

    /** The whole body following [head], or null when an ETag changed. */
    private suspend fun download(peer: Endpoint, request: CoapMessage, head: CoapMessage, confirmable: Boolean): CoapResponse? {
        val announced = head.options.size2
        if (announced != null && announced > config.maxResponseBodySize) throw tooLarge(announced)
        val etag = head.options.etags.firstOrNull()?.toHex()
        val body = ByteArrayBuilder(maxOf(head.payloadSize, (announced ?: 0).toInt()))
        body.append(head.payload)
        var size = head.options.block2!!.size
        while (true) {
            val response = client.exchange(peer, followUp(request, (body.size / size).toLong(), size), confirmable)
            if (!response.isResponse || !response.code.isSuccess) {
                throw CoapException("Block2 transfer failed with ${response.code}", CoapException.Reason.BLOCKWISE_FAILED)
            }
            val block = response.options.block2
                ?: throw CoapException("A follow-up response lacks Block2", CoapException.Reason.BLOCKWISE_FAILED)
            if (response.options.etags.firstOrNull()?.toHex() != etag) return null
            if (block.offset != body.size.toLong()) {
                throw CoapException("Block ${block.number} does not continue at ${body.size}", CoapException.Reason.BLOCKWISE_FAILED)
            }
            body.append(response.payload)
            if (body.size > config.maxResponseBodySize) throw tooLarge(body.size.toLong())
            if (!block.more) return withoutBlockOptions(head, body.toByteArray())
            size = block.size
        }
    }

    private fun followUp(request: CoapMessage, number: Long, size: Int): CoapMessage = request.toBuilder().apply {
        // §2.4: further requests repeat the options with the Block2 control option (M = 0).
        payload = if (request.code == CoapCode.FETCH) request.payload else ByteArray(0)
        options {
            remove(CoapOptions.BLOCK1.number)
            remove(CoapOptions.SIZE1.number)
            remove(CoapOptions.OBSERVE.number)
            block2(Blockwise.block(number, false, size))
        }
    }.build()

    private fun withoutBlockOptions(response: CoapMessage, body: ByteArray): CoapResponse {
        val options = response.options.toBuilder().apply {
            remove(CoapOptions.BLOCK2.number)
            remove(CoapOptions.SIZE2.number)
        }.build()
        return CoapResponse(response.code, options, body)
    }

    private fun tooLarge(size: Long) = CoapException(
        "Response body of $size bytes exceeds ${config.maxResponseBodySize}",
        CoapException.Reason.BLOCKWISE_FAILED,
    )

    /**
     * RFC 9175 §3.5.2: a Block1 operation matchable to one in progress gets a Request-Tag not used by it;
     * the absent option is the first choice, as the RFC encourages compact messages (§3.4).
     */
    private suspend fun acquireTag(key: OperationKey): ByteArray? = tagLock.withLock {
        val used = tagsInUse.getOrPut(key) { mutableSetOf() }
        // Candidate 0 is the absent option; others are short values, never the empty one, which differs from absence.
        val candidate = generateSequence(0L) { it + 1 }.first { used.add(tagKey(it)) }
        if (candidate == 0L) null else minimalUnsignedBytes(candidate)
    }

    private fun tagKey(candidate: Long): String = if (candidate == 0L) ABSENT else minimalUnsignedBytes(candidate).toHex()

    private suspend fun releaseTag(key: OperationKey, tag: ByteArray?) {
        tagLock.withLock {
            val used = tagsInUse[key] ?: return@withLock
            used.remove(tag?.toHex() ?: ABSENT)
            if (used.isEmpty()) tagsInUse.remove(key)
        }
    }

    private companion object {
        const val MAX_RESTARTS = 2
        const val ABSENT = "absent"
    }
}

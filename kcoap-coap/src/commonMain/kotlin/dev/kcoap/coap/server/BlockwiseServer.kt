package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.BlockSizing
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.ExpiringCache
import dev.kcoap.coap.stack.MessageLimits
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.common.ByteArrayBuilder
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The server side of RFC 7959: assembling Block1 request bodies atomically, and serving large responses
 * in Block2 blocks from a cached representation. State is dropped EXCHANGE_LIFETIME after its last use
 * (RFC 7959 §2.4, §2.5) or when [CoapServerConfig.maxConcurrentTransfers] is exceeded.
 *
 * Block sizes follow the [MessageLimits] of each peer: its datagram size, or on a connection its
 * Max-Message-Size, with BERT blocks for peers that indicated BERT (RFC 8323 §6).
 */
internal class BlockwiseServer(
    private val config: CoapServerConfig,
    lifetime: Duration,
) {
    private val lock = Mutex()
    private val uploads = ExpiringCache<OperationKey, Upload>(config.maxConcurrentTransfers, lifetime)
    private val representations = ExpiringCache<OperationKey, CoapResponse>(config.maxConcurrentTransfers, lifetime)

    private class Upload {
        val body = ByteArrayBuilder()
    }

    sealed interface Block1Result {
        /** Answer this block with [response]: 2.31 Continue or an error. */
        class Respond(val response: CoapResponse) : Block1Result

        /** The last block arrived: [request] carries the whole body; [control] goes into the final response. */
        class Assembled(val request: CoapMessage, val control: BlockOption) : Block1Result
    }

    /**
     * Processes one block of a Block1 transfer (RFC 7959 §2.5, Figures 7 and 9). A BERT block (RFC 8323 §6)
     * is a run of 1024-byte blocks starting at its number; it is acknowledged as BERT when [limits] allow
     * BERT, otherwise with SZX 6, which asks the client for plain blocks.
     */
    suspend fun receiveBlock1(
        key: OperationKey,
        request: CoapMessage,
        block: BlockOption,
        now: Duration,
        limits: MessageLimits,
    ): Block1Result =
        lock.withLock {
            val payload = request.payload
            val options = request.options
            // §2.3: with M set, the payload size MUST match SZX; RFC 8323 §6: a non-final BERT block is a multiple of 1024.
            val sizeMatches = if (block.isBert) payload.isNotEmpty() && payload.size % block.size == 0 else payload.size == block.size
            if (block.more && !sizeMatches) {
                return@withLock reject(CoapCode.BAD_REQUEST, "Block1 payload of ${payload.size} bytes does not match ${block.size}")
            }
            options.size1?.let { announced ->
                if (announced > config.maxBodySize) return@withLock tooLarge(key)
            }
            // The key holds every option but the Block and NoCacheKey ones (RFC 9175 §3.3), Content-Format
            // and Request-Tag included, so a block with another Content-Format finds no earlier blocks and
            // is never assembled with them (RFC 7959 §2.3).
            val upload = if (block.number == 0L) {
                Upload().also { uploads.put(key, it, now) }
            } else {
                uploads.get(key, now)
                    ?: return@withLock reject(CoapCode.REQUEST_ENTITY_INCOMPLETE, "No earlier blocks of this body")
            }
            val offset = block.offset
            val received = upload.body.size.toLong()
            when {
                offset == received -> upload.body.append(payload)
                // A block sent again after its response was lost: already have it.
                offset < received && offset + payload.size <= received && block.more -> Unit
                else -> {
                    uploads.remove(key)
                    return@withLock reject(CoapCode.REQUEST_ENTITY_INCOMPLETE, "Block ${block.number} is out of sequence")
                }
            }
            if (upload.body.size > config.maxBodySize) return@withLock tooLarge(key)
            // §2.3: the control SZX is the server's preference, at most what the client used.
            val exponent = if (block.isBert && limits.allowsBert && config.maxBlockSize == Blockwise.MAX_BLOCK_SIZE) {
                block.sizeExponent
            } else {
                minOf(block.sizeExponent, preferredExponent())
            }
            val control = BlockOption(block.number, block.more, exponent)
            if (block.more) {
                return@withLock Block1Result.Respond(CoapResponse(CoapCode.CONTINUE).withOptions { block1(control) })
            }
            uploads.remove(key)
            val assembled = request.toBuilder().apply {
                this.payload = upload.body.toByteArray()
                options {
                    remove(CoapOptions.BLOCK1.number)
                    remove(CoapOptions.SIZE1.number)
                }
            }.build()
            Block1Result.Assembled(assembled, control)
        }

    private fun preferredExponent(): Int = BlockOption.sizeExponentFor(config.maxBlockSize)

    private fun reject(code: CoapCode, diagnostic: String) = Block1Result.Respond(CoapResponse.error(code, diagnostic))

    /** §2.9.3: 4.13 with Size1 giving the largest acceptable body. */
    private fun tooLarge(key: OperationKey): Block1Result {
        uploads.remove(key)
        return Block1Result.Respond(
            CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE).withOptions { size1(config.maxBodySize.toLong()) },
        )
    }

    /** The representation a follow-up Block2 request continues, if still cached. */
    suspend fun representation(key: OperationKey, now: Duration): CoapResponse? = lock.withLock { representations.get(key, now) }

    /**
     * Turns [response] to [request] into the block the request asks for (RFC 7959 §2.4): unchanged if it
     * fits one message within [limits] and no block was asked for, else block NUM of the requested or
     * largest fitting size, with an ETag (added if missing) and Size2. To a peer that indicated BERT and
     * asked for no smaller block, the blocks are BERT blocks (RFC 8323 §6). Multi-block representations
     * are cached under [key] so that the following blocks come from the same representation.
     *
     * @param requested the Block2 option of the request, or the block size an observer asked for.
     */
    suspend fun serve(
        key: OperationKey,
        token: ByteArray,
        requested: BlockOption?,
        response: CoapResponse,
        reserve: Int,
        now: Duration,
        limits: MessageLimits,
    ): CoapResponse {
        if (requested == null && Blockwise.fits(response.toMessage(token), limits, reserve)) return response
        val body = response.payload
        val needsETag = response.options.etags.isEmpty()
        // The largest header a block can have: an ETag of 8 bytes, a 3-byte Block2 and Size2.
        val header = response.withOptions {
            if (needsETag) etag(ByteArray(8))
            block2(BlockOption(BlockOption.MAX_NUMBER, true, 6))
            size2(body.size.toLong())
        }
        val limit = minOf(config.maxBlockSize, requested?.size ?: Blockwise.MAX_BLOCK_SIZE)
        val bertWanted = config.maxBlockSize == Blockwise.MAX_BLOCK_SIZE && (requested == null || requested.isBert)
        val sizing = (if (bertWanted) Blockwise.bertSizing(limits, header.toMessage(token), reserve) else null)
            ?: Blockwise.fittingBlockSize(limits, header.toMessage(token), reserve, limit)?.let(BlockSizing::of)
            ?: return CoapResponse.error(CoapCode.INTERNAL_SERVER_ERROR, "Response options do not fit a message")
        val full = if (needsETag && body.size > sizing.chunk) response.withOptions { etag(Blockwise.etagOf(body)) } else response
        // A request for a larger block than fits is answered from the same offset in smaller blocks.
        val offset = if (requested == null) 0 else requested.offset / sizing.unit * sizing.unit
        val (slice, more) = Blockwise.slice(body, offset, sizing.length(offset, body.size))
            ?: return CoapResponse.error(CoapCode.BAD_OPTION, "Block ${requested?.number} is beyond the end of the body")
        if (body.size > sizing.chunk) {
            // Later blocks are plain responses: a notification's Observe stays with its first block.
            val stored = full.withOptions { remove(CoapOptions.OBSERVE.number) }
            lock.withLock { representations.put(key, stored, now) }
        }
        return CoapResponse(
            full.code,
            full.options.toBuilder().apply {
                block2(sizing.option(offset, more))
                size2(body.size.toLong())
            }.build(),
            slice,
        )
    }

    /** Drops every transfer of [peer], whose endpoint is gone. */
    suspend fun forget(peer: Endpoint) {
        lock.withLock {
            uploads.removeAll { it.peer == peer }
            representations.removeAll { it.peer == peer }
        }
    }
}

package dev.kcoap.coap.client

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.stack.BodyLease
import dev.kcoap.coap.stack.BodyMode
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.ClientExchange
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.ExchangeEvent
import dev.kcoap.coap.stack.ExchangeInbox
import dev.kcoap.coap.stack.MessageLimits
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.Pacing
import dev.kcoap.coap.stack.QBlock1Sender
import dev.kcoap.coap.stack.QBlock2Receiver
import dev.kcoap.coap.stack.QBlockAction
import dev.kcoap.coap.stack.QBlockCapabilities
import dev.kcoap.coap.stack.QBlockCheck
import dev.kcoap.coap.stack.QBlockTiming
import dev.kcoap.coap.stack.ResponseMode
import dev.kcoap.log.LogTags
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * The client side of Q-Block (RFC 9177): it decides whether a request goes with Q-Block or with RFC 7959, and
 * drives the sans-IO cores ([QBlock1Sender] for request bodies, [QBlock2Receiver] for response bodies) over
 * the endpoint's exchanges, timers and body lease. The Q-Block rules are the cores'; this class turns their
 * actions into messages and their deadlines into waits.
 *
 * **Choosing the mechanism** (§4.1). RFC 7959 ([BlockwiseClient]) carries the request when Q-Block is off,
 * when the peer is on a reliable transport and [QBlockClientConfig.onReliableTransports] is off, when message
 * security is configured (whatever [QBlockClientConfig.withMessageSecurity] says, until OSCORE takes several
 * responses to one request), when the request carries No-Response (its combination with Q-Block1 is
 * undefined), Observe, or a Block or Q-Block option of its own, and when [QBlockSupport] does not know the
 * peer to support Q-Block.
 *
 * **Requests.** A body that does not fit one message, or is larger than [CoapClientConfig.blockSize], goes in
 * Q-Block1 blocks of one size for the whole body, each a new request on a new token with Size1, the body's
 * Request-Tag (a 4-byte counter with a random start, never reused while the client lives, §4.3) and the
 * request's other options. Every other request carries Q-Block2 0/M=1 with the proposed block size, which
 * tells the server that the client takes a Q-Block2 body (§4.1, §4.4). Bodies follow the request's message
 * type (§7): Non-confirmable bodies hold one [BodyLease] for their whole life (§7.2), Confirmable ones take a
 * slot per message (§7.1).
 *
 * **Responses.** Every request of a body reports to one [ExchangeInbox], so a response counts on whichever
 * token of the body it comes (§4.3, §4.4). A Q-Block2 body, also one that answers an upload (§4.7), is
 * assembled by [QBlock2Receiver]; its Continues and requests for missing blocks repeat the request's method
 * and options without Observe, ETag, payload (a FETCH keeps its payload unless it was uploaded with Q-Block1)
 * or Q-Block1, with the Request-Tag of the upload. Tokens are kept until the body ends, except those the
 * cores release and, for a long download, the oldest of [MAX_OPEN_FOLLOW_UPS] follow-ups. On a reliable
 * transport nothing is asked for again (Appendix B), so the inbox there has room for every block of the
 * largest body ([ExchangeInbox.forReliableBody]) and never drops one.
 *
 * **Fallback.** A 4.02 or a Reset that answers a body before anything else did shows a server that does not
 * take Q-Block after all; such a request was not processed (RFC 7252 §5.4.1), so it is sent again with
 * RFC 7959 ([send] returns null), and [QBlockSupport.rejected] is told. Anything else counts first: an Empty
 * ACK, a 2.31, a 4.08 or a block shows that the server took Q-Block, so a later 4.02 rejected some other
 * option and is the answer. A 4.01 with Echo (RFC 9175 §2.3) does not count.
 *
 * **Echo.** A 4.01 with Echo to the request of a download, or to a Continue or a request for missing blocks,
 * has that request sent again once with the Echo value, which every later message of the body carries too.
 *
 * **Silence.** A Non-confirmable download whose request gets no response at all is sent again on the
 * NON_RECEIVE_TIMEOUT ladder, at most NON_MAX_RETRANSMIT times, if its method may be repeated (GET, FETCH,
 * PUT, DELETE, iPATCH); POST and PATCH, or the end of the ladder, fail with
 * [CoapException.Reason.RESPONSE_TIMEOUT]. The request sent again with Echo starts that ladder anew. A
 * Non-confirmable body that never got a response forgets the peer's capability and leaves its lease to
 * expire, so the next body waits as PROBING_RATE asks (§7.2). Otherwise a body that hears nothing for
 * [CoapClient.requestTimeout] fails the same way.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class QBlockClient(private val client: CoapClient) {
    private val config = client.config.qBlock
    private val endpoint = client.endpoint
    private val timing = QBlockTiming(endpoint.parameters)
    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_CLIENT)

    /** Which servers support Q-Block. */
    val support = QBlockSupport(client, config, log)

    /** The next Request-Tag, as an unsigned 4-byte counter that starts at a random value (RFC 9177 §4.3). */
    private val nextTag = AtomicInt(endpoint.config.random.nextInt())

    init {
        if (config.withMessageSecurity && client.config.security != null) {
            log.warn { "QBlockClientConfig.withMessageSecurity has no effect yet: protected requests use RFC 7959" }
        }
    }

    /**
     * Sends [request] with Q-Block and returns its whole response, or returns null when RFC 7959 must carry
     * it: Q-Block does not apply to it or to [peer], or the peer rejected it without processing it.
     */
    suspend fun send(peer: Endpoint, request: CoapMessage, confirmable: Boolean): CoapResponse? {
        val reliable = endpoint.isReliable(peer)
        rfc7959Reason(request, reliable)?.let { reason ->
            log.debug { "RFC 7959 for ${request.code} ${request.options.path}: $reason" }
            return null
        }
        val isProtected = client.config.security != null
        if (support.capability(peer, isProtected) != QBlockCapabilities.Capability.SUPPORTED) return null
        val limits = endpoint.limits(peer)
        val transfer = Transfer(peer, request, confirmable, reliable, limits, isProtected)
        return when (val outcome = transfer.run()) {
            is Outcome.Done -> outcome.response
            is Outcome.Fallback -> {
                support.rejected(peer, isProtected, outcome.reason)
                null
            }
            // The server answered with Block2: its other blocks are fetched with RFC 7959 only.
            is Outcome.Rfc7959 -> client.blockwise.complete(peer, outcome.followUps, outcome.first, confirmable)
        }
    }

    /** Why [request] goes with RFC 7959 whatever the peer supports, or null when Q-Block may carry it. */
    private fun rfc7959Reason(request: CoapMessage, reliable: Boolean): String? {
        val options = request.options
        return when {
            reliable && !config.onReliableTransports -> "Q-Block is off on reliable transports"
            // OSCORE takes one response per request (RFC 8613 §7.4), so withMessageSecurity cannot take effect yet.
            client.config.security != null -> "Q-Block is not available with message security yet"
            options.noResponse != null -> "the request carries No-Response"
            options.observe != null -> "the request carries Observe"
            OWN_BLOCK_OPTIONS.any { it in options } -> "the request carries block options of its own"
            else -> null
        }
    }

    private fun nextRequestTag(): ByteArray {
        val value = nextTag.fetchAndIncrement()
        return ByteArray(4) { (value ushr (24 - 8 * it)).toByte() }
    }

    /** How a [Transfer] ended, when it did not fail. */
    private sealed interface Outcome {
        class Done(val response: CoapResponse) : Outcome

        /** The peer rejected Q-Block before processing the request; it is sent again with RFC 7959. */
        class Fallback(val reason: String) : Outcome

        /** The peer answered with a Block2 body: [first] block, then RFC 7959 follow-ups based on [followUps]. */
        class Rfc7959(val first: CoapMessage, val followUps: CoapMessage) : Outcome
    }

    /**
     * A request of a body: its exchange, the upload's sequence number for a block, the generation of the
     * upload, and for a Continue or a request for missing blocks its Q-Block2 options ([blocks]) and whether
     * it repeats one that a 4.01 with Echo answered ([isEchoRetry]).
     */
    private class Sent(
        val exchange: ClientExchange,
        val sequence: Int?,
        val generation: Int,
        val blocks: List<BlockOption>? = null,
        val isEchoRetry: Boolean = false,
    )

    /**
     * One request and its body or bodies, from the first message to the end: an upload (Q-Block1), a
     * download (Q-Block2), or an upload whose response is a download (§4.7). Runs in the caller's coroutine;
     * not thread safe.
     */
    private inner class Transfer(
        private val peer: Endpoint,
        private val request: CoapMessage,
        private val confirmable: Boolean,
        reliable: Boolean,
        private val limits: MessageLimits,
        private val isProtected: Boolean,
    ) {
        private val mode = BodyMode.of(if (confirmable) CoapMessageType.CON else CoapMessageType.NON, reliable)
        private val method = request.code
        private val inbox = if (mode == BodyMode.RELIABLE) {
            ExchangeInbox.forReliableBody(endpoint.parameters, client.config.maxResponseBodySize)
        } else {
            ExchangeInbox.forBody(endpoint.parameters)
        }
        private val logger = log.with("peer" to peer, "path" to request.options.path)
        private var lease: BodyLease? = null

        /** Whether the lease is left to expire: a Non-confirmable body that got no response at all (§7.2). */
        private var keepsLease = false

        /** The requests of the body, by token. */
        private val sent = LinkedHashMap<String, Sent>()

        /** Continues and requests for missing blocks, oldest first. */
        private val followUps = ArrayDeque<ClientExchange>()

        /** The Echo value a 4.01 asked for (RFC 9175 §2.3), sent on every later message. */
        private var echo: ByteArray? = null

        /**
         * Whether the server answered any request of the body: a response other than a 4.01 with Echo, or an
         * Empty ACK. Until then a 4.02 or a Reset rejects Q-Block, and a Non-confirmable download is sent again.
         */
        private var answered = false
        private var lastActivity = endpoint.now()

        // Upload (Q-Block1).
        private val body = request.payload
        private val base = request.toBuilder().apply {
            payload = ByteArray(0)
            options { remove(CoapOptions.REQUEST_TAG.number) }
        }.build()
        private var sender: QBlock1Sender? = null
        private var generation = 0
        private var requestTag = ByteArray(0)
        private val sequences = HashMap<Int, String>()

        // Download (Q-Block2).
        private var receiver: QBlock2Receiver? = null
        private var followUpBase: CoapMessage = request
        private var sizeExponent = 6
        private var hasBlock = false

        /** For a download alone: the request itself, how often it was sent again, and when it was last sent. */
        private var original: CoapMessage? = null
        private var originalResends = 0
        private var originalSentAt = Duration.ZERO

        private var outcome: Outcome? = null

        suspend fun run(): Outcome {
            try {
                val upload = uploadBlockSize()
                if (mode == BodyMode.NON) {
                    val bytes = if (upload != null) body.size else UdpMessageCodec.encode(request).size
                    lease = endpoint.leaseBody(peer, bytes)
                }
                if (upload != null) startUpload(upload, mayRestart = true) else startDownload()
                while (true) {
                    outcome?.let { return it }
                    val deadline = nextDeadline()
                    val wait = deadline - endpoint.now()
                    val event = if (wait.isPositive()) inbox.receive(wait) else inbox.tryReceive()
                    if (event == null) onTimer() else onEvent(event)
                }
            } finally {
                withContext(NonCancellable) { end() }
            }
        }

        /** Forgets every token of the body (RFC 9177 §4.3) and gives the lease back. */
        private suspend fun end() {
            for (request in sent.values.toList()) request.exchange.cancel()
            sent.clear()
            followUps.clear()
            lease?.let { if (!keepsLease) endpoint.releaseBody(it) }
        }

        // ---------------------------------------------------------------- upload

        /**
         * The block size for an upload, or null when the body fits one message with Q-Block2, as the
         * request then goes whole (§4.3). One size serves the whole body, chosen to leave room for Q-Block1,
         * Size1 and Request-Tag next to the request's options.
         */
        private fun uploadBlockSize(): Int? {
            if (body.isEmpty()) return null
            val reserve = client.securityReserve
            val single = request.toBuilder().options { qBlock2(BlockOption(0, true, downloadSizeExponent())) }.build()
            val limit = client.config.blockSize
            if (limits.fits(single, reserve) && body.size <= (limit ?: Int.MAX_VALUE)) return null
            val header = base.toBuilder().options {
                qBlock1(BlockOption(BlockOption.MAX_NUMBER, true, MAX_EXPONENT))
                size1(body.size.toLong())
                requestTag(ByteArray(TAG_LENGTH))
            }.build()
            return Blockwise.fittingBlockSize(limits, header, reserve, limit ?: Blockwise.MAX_BLOCK_SIZE)
                ?: throw CoapException("Request options leave no room for a Q-Block1 block", CoapException.Reason.MESSAGE_TOO_LARGE)
        }

        private suspend fun startUpload(blockSize: Int, mayRestart: Boolean) {
            generation++
            requestTag = nextRequestTag()
            sequences.clear()
            val sender = QBlock1Sender(
                size1 = body.size,
                blockSize = blockSize,
                mode = mode,
                timing = timing,
                nonTimeoutRandom = nonTimeoutRandom(),
                resendsLastBlock = method in REPEATABLE_WITH_BODY,
                mayRestart = mayRestart,
                allowsBert = limits.allowsBert,
            )
            this.sender = sender
            logger.debug { "Q-Block1 ${method} of ${body.size} bytes in ${sender.count} blocks of $blockSize, $mode" }
            uploadActions(sender.start(endpoint.now()))
        }

        private suspend fun uploadActions(actions: List<QBlockAction<Int>>) {
            val sender = checkNotNull(sender)
            for (action in actions) {
                when (action) {
                    is QBlockAction.SendBlock -> sendBlock(sender, action.num, action.more, action.ref)
                    is QBlockAction.Release -> action.refs.forEach { sequence -> sequences.remove(sequence)?.let { release(it) } }
                    is QBlockAction.Deliver -> onUploadResponse(checkNotNull(action.ref), action.message)
                    is QBlockAction.Fail -> fail(action.reason, action.diagnostic)
                    is QBlockAction.Restart -> {
                        logger.debug { "4.13: the upload starts again with blocks of ${action.blockSize} bytes" }
                        startUpload(action.blockSize, mayRestart = false)
                    }
                    is QBlockAction.Drop -> logger.debug { "Dropped a response: ${action.reason}" }
                    else -> Unit
                }
            }
        }

        private suspend fun sendBlock(sender: QBlock1Sender, num: Long, more: Boolean, sequence: Int) {
            val offset = (num * sender.blockSize).toInt()
            val block = base.toBuilder().apply {
                payload = body.copyOfRange(offset, minOf(body.size, offset + sender.blockSize))
                options {
                    qBlock1(BlockOption(num, more, sender.sizeExponent))
                    size1(body.size.toLong())
                    requestTag(requestTag)
                    echo?.let { echo(it) }
                }
            }.build()
            val exchange = open(block, sequence)
            sequences[sequence] = exchange.key.token
        }

        /** The response [message] ended the upload; it arrived on [sequence]. */
        private suspend fun onUploadResponse(sequence: Int, message: CoapMessage) {
            if (isRejection(message)) return
            val block = message.options.qBlock2
            when {
                block != null && (block.number != 0L || block.more) -> {
                    // §4.7: the response body follows in Q-Block2, asked for with the method, options and
                    // Request-Tag of the upload, without Q-Block1 and payload.
                    followUpBase = base.toBuilder().options { requestTag(requestTag) }.build()
                    startReceiver()
                    onBlock(message)
                }
                message.options.block2?.more == true ->
                    outcome = Outcome.Rfc7959(message, base.toBuilder().options { requestTag(requestTag) }.build())
                else -> done(message)
            }
            logger.debug { "Upload ended with ${message.code} on request $sequence" }
        }

        // ---------------------------------------------------------------- download

        private suspend fun startDownload() {
            followUpBase = request.toBuilder().apply {
                if (method != CoapCode.FETCH) payload = ByteArray(0)
            }.build()
            sizeExponent = downloadSizeExponent()
            val message = request.toBuilder().options { qBlock2(BlockOption(0, true, sizeExponent)) }.build()
            original = message
            sendOriginal(message)
        }

        private suspend fun sendOriginal(message: CoapMessage) {
            originalSentAt = endpoint.now()
            open(message.withEcho(), sequence = null)
        }

        /** The SZX proposed for response blocks: the configured block size, or the largest that fits. */
        private fun downloadSizeExponent(): Int {
            val estimate = CoapMessage.build {
                code = CoapCode.CONTENT
                token = ByteArray(CoapMessage.MAX_TOKEN_LENGTH)
                options {
                    etag(ByteArray(MAX_ETAG_LENGTH))
                    size2(UInt.MAX_VALUE.toLong())
                    qBlock2(BlockOption(BlockOption.MAX_NUMBER, true, MAX_EXPONENT))
                    contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ)
                    maxAge(UInt.MAX_VALUE.toLong())
                }
            }
            val size = client.config.blockSize
                ?: Blockwise.fittingBlockSize(limits, estimate, client.securityReserve)
                ?: Blockwise.MIN_BLOCK_SIZE
            return BlockOption.sizeExponentFor(size)
        }

        private fun startReceiver() {
            receiver = QBlock2Receiver(
                mode = mode,
                timing = timing,
                maxBodySize = client.config.maxResponseBodySize,
                restartable = method == CoapCode.GET || method == CoapCode.FETCH,
                missingBlockMBit = config.missingBlockMBit,
                allowsBert = limits.allowsBert,
            )
        }

        /** A response, on the request [origin], that is not part of an upload in progress. */
        private suspend fun onDownloadResponse(origin: Sent, message: CoapMessage) {
            val violation = QBlockCheck.response(message, limits.allowsBert)
            if (violation != null) {
                if (hasBlock) return logger.debug { "Dropped a response: $violation" }
                fail(CoapException.Reason.BAD_RESPONSE, "Response rejected: $violation")
            }
            val block = message.options.qBlock2
            if (block != null) return onBlock(message)
            val echoValue = message.options.echo
            val isChallenge = message.code == CoapCode.UNAUTHORIZED && echoValue != null && client.config.echoRetry
            if (hasBlock) {
                // A request for blocks was answered with something else than a block.
                if (message.code.isSuccess) return logger.debug { "Dropped a ${message.code} without Q-Block2" }
                val blocks = origin.blocks
                if (isChallenge && blocks != null && !origin.isEchoRetry) {
                    // RFC 9175 §2.3: a server that checks addresses challenges a client whose address changed.
                    logger.debug { "4.01 with Echo to a request for blocks; repeating it with the Echo value" }
                    echo = echoValue
                    sendFollowUp(blocks, isEchoRetry = true)
                    return
                }
                fail(CoapException.Reason.BLOCKWISE_FAILED, "A request for blocks was answered with ${message.code}")
            }
            val original = original
            if (isChallenge && echo == null && original != null) {
                logger.debug { "4.01 with Echo; repeating the request with it" }
                echo = echoValue
                // The repeat gets the whole ladder of a request without answer (§7.2).
                originalResends = 0
                sendOriginal(original)
                return
            }
            if (isRejection(message)) return
            if (message.options.block2?.more == true) {
                outcome = Outcome.Rfc7959(message, followUpBase)
                return
            }
            done(message)
        }

        private suspend fun onBlock(message: CoapMessage) {
            val block = checkNotNull(message.options.qBlock2)
            if (!hasBlock && block.number == 0L && !block.more) {
                // A body of one block needs no assembly, and libcoap sends it without ETag and Size2.
                return done(message)
            }
            hasBlock = true
            sizeExponent = block.sizeExponent
            val receiver = receiver ?: run {
                startReceiver()
                checkNotNull(receiver)
            }
            downloadActions(receiver.onBlock(endpoint.now(), message))
        }

        private suspend fun downloadActions(actions: List<QBlockAction<Nothing>>) {
            for (action in actions) {
                when (action) {
                    is QBlockAction.SendContinue -> sendFollowUp(listOf(BlockOption(action.num, true, sizeExponent)))
                    is QBlockAction.RequestMissing -> {
                        endpoint.report(EndpointEvent.QBlockRecovery(peer, isRequestedByPeer = false, blocks = action.blocks.map { it.number }))
                        sendFollowUp(action.blocks)
                    }
                    is QBlockAction.Deliver -> done(action.message)
                    is QBlockAction.Fail -> fail(action.reason, action.diagnostic)
                    is QBlockAction.Discard -> {
                        endpoint.report(EndpointEvent.PartialBodyDropped(peer, action.reason))
                        fail(CoapException.Reason.BLOCKWISE_FAILED, "The response body was given up: ${action.reason}")
                    }
                    is QBlockAction.Drop -> logger.debug { "Dropped a block: ${action.reason}" }
                    else -> Unit
                }
            }
        }

        /**
         * A Continue or a request for missing blocks (§4.4): the request without Observe, ETag, payload (but
         * a FETCH's) or Q-Block1, with one Q-Block2 option per entry of [blocks].
         */
        private suspend fun sendFollowUp(blocks: List<BlockOption>, isEchoRetry: Boolean = false) {
            val message = followUpBase.toBuilder().options {
                remove(CoapOptions.OBSERVE.number)
                remove(CoapOptions.ETAG.number)
                remove(CoapOptions.Q_BLOCK1.number)
                remove(CoapOptions.Q_BLOCK2.number)
                remove(CoapOptions.SIZE1.number)
                blocks.forEach { addQBlock2(it) }
            }.build()
            val exchange = open(message.withEcho(), sequence = null, blocks = blocks, isEchoRetry = isEchoRetry)
            followUps.addLast(exchange)
            while (followUps.size > MAX_OPEN_FOLLOW_UPS) release(followUps.removeFirst().key.token)
        }

        // ---------------------------------------------------------------- events and time

        private suspend fun onEvent(event: ExchangeEvent) {
            val origin = sent[event.exchange.key.token] ?: return
            val now = endpoint.now()
            lastActivity = now
            val sender = sender?.takeIf { !it.isFinished && origin.generation == generation }
            val sequence = origin.sequence?.takeIf { sender != null }
            when (event) {
                is ExchangeEvent.Response -> {
                    val message = event.message
                    if (sender != null && sequence != null) onUploadEvent(sender, sequence, message) else onDownloadResponse(origin, message)
                    if (message.code != CoapCode.UNAUTHORIZED || message.options.echo == null) answered = true
                }
                is ExchangeEvent.Acknowledged -> {
                    answered = true
                    if (sender != null && sequence != null) uploadActions(sender.onAcknowledged(now, sequence))
                }
                is ExchangeEvent.Reset -> {
                    sent.remove(event.exchange.key.token)
                    when {
                        !answered -> outcome = Outcome.Fallback("${event.exchange.request?.code} was answered with a Reset")
                        sender != null && sequence != null -> {
                            support.rejected(peer, isProtected, "A Q-Block1 block was answered with a Reset")
                            uploadActions(sender.onReset(now, sequence))
                        }
                        else -> logger.debug { "A request for blocks was answered with a Reset" }
                    }
                }
                is ExchangeEvent.TimedOut -> {
                    sent.remove(event.exchange.key.token)
                    when {
                        sender != null && sequence != null -> uploadActions(sender.onTimeout(now, sequence))
                        origin.sequence != null || hasBlock -> logger.debug { "A request of the body was not acknowledged" }
                        else -> fail(CoapException.Reason.TRANSMISSION_TIMEOUT, "No ACK from $peer after MAX_RETRANSMIT retransmissions")
                    }
                }
                // The endpoint forgot the peer's capability already if its session ended or its connection closed.
                is ExchangeEvent.Failed -> throw event.cause
            }
        }

        private suspend fun onUploadEvent(sender: QBlock1Sender, sequence: Int, message: CoapMessage) {
            val echoValue = message.options.echo
            if (message.code == CoapCode.UNAUTHORIZED && echoValue != null) {
                if (!client.config.echoRetry) {
                    uploadActions(sender.abandon())
                    return done(message)
                }
                echo = echoValue
            }
            val actions = sender.onResponse(endpoint.now(), sequence, message)
            val isMissingList = message.code == CoapCode.REQUEST_ENTITY_INCOMPLETE &&
                message.options.contentFormat == ContentFormat.MISSING_BLOCKS_CBOR_SEQ
            if (isMissingList && actions.any { it is QBlockAction.SendBlock<*> }) {
                val listed = MissingBlocks.decode(message.payload).orEmpty().filter { it < sender.count }.take(timing.maxPayloads)
                endpoint.report(EndpointEvent.QBlockRecovery(peer, isRequestedByPeer = true, blocks = listed))
            }
            uploadActions(actions)
        }

        private fun nextDeadline(): Duration {
            val machine = sender?.takeIf { !it.isFinished }?.nextDeadline() ?: receiver?.nextDeadline()
            return listOfNotNull(machine, unansweredDeadline(), lastActivity + client.requestTimeout).min()
        }

        /**
         * For a Non-confirmable download that got no response at all: when to send the request again, on
         * the NON_RECEIVE_TIMEOUT ladder if its method may be repeated, or when to give up, after the whole
         * ladder (124 s by default) for POST and PATCH, as an upload does after its last block.
         */
        private fun unansweredDeadline(): Duration? {
            if (answered || original == null || mode != BodyMode.NON) return null
            return originalSentAt + if (method in REPEATABLE) timing.receiveWait(originalResends) else timing.receiveLadder
        }

        private suspend fun onTimer() {
            val now = endpoint.now()
            sender?.takeIf { !it.isFinished }?.let { sender ->
                if (sender.nextDeadline()?.let { it <= now } == true) uploadActions(sender.onTimer(now))
            }
            if (outcome != null) return
            receiver?.let { receiver ->
                if (receiver.nextDeadline()?.let { it <= now } == true) downloadActions(receiver.onTimer(now))
            }
            if (outcome != null) return
            val original = original
            val unanswered = unansweredDeadline()
            if (original != null && unanswered != null && now >= unanswered) {
                if (method in REPEATABLE && originalResends < timing.nonMaxRetransmit) {
                    originalResends++
                    logger.debug { "No response; sending the request again ($originalResends)" }
                    sendOriginal(original)
                    return
                }
                fail(CoapException.Reason.RESPONSE_TIMEOUT, "No response from $peer")
            }
            if (now >= lastActivity + client.requestTimeout) {
                fail(CoapException.Reason.RESPONSE_TIMEOUT, "No response from $peer within ${client.requestTimeout}")
            }
        }

        // ---------------------------------------------------------------- helpers

        private suspend fun open(
            message: CoapMessage,
            sequence: Int?,
            blocks: List<BlockOption>? = null,
            isEchoRetry: Boolean = false,
        ): ClientExchange {
            val lease = lease
            val exchange = endpoint.openExchange(
                peer, message, confirmable, client.config.security, ResponseMode.Inbox(inbox),
                pacing = if (lease != null) Pacing.Body(lease) else Pacing.PerMessage,
                recognizedOptions = RECOGNIZED_OPTIONS,
            )
            sent[exchange.key.token] = Sent(exchange, sequence, generation, blocks, isEchoRetry)
            lastActivity = endpoint.now()
            return exchange
        }

        private suspend fun release(token: String) {
            sent.remove(token)?.exchange?.cancel()
        }

        private fun CoapMessage.withEcho(): CoapMessage = echo?.let { value -> toBuilder().options { echo(value) }.build() } ?: this

        private fun nonTimeoutRandom(): Duration = lease?.nonTimeoutRandom ?: timing.drawNonTimeoutRandom(endpoint.config.random)

        /**
         * Whether [message] is a 4.02 that rejects Q-Block (§4.1): one before the server answered anything of
         * the body, so the request falls back to RFC 7959. Once the server took part of the body, a 4.02
         * rejected another option (RFC 7252 §5.4.1) and is the answer; the peer's capability stays.
         */
        private fun isRejection(message: CoapMessage): Boolean {
            if (message.code != CoapCode.BAD_OPTION || answered) return false
            outcome = Outcome.Fallback("A Q-Block request was answered with 4.02")
            return true
        }

        private fun done(message: CoapMessage) {
            val options = message.options.toBuilder().apply {
                remove(CoapOptions.Q_BLOCK1.number)
                remove(CoapOptions.Q_BLOCK2.number)
                remove(CoapOptions.SIZE2.number)
            }.build()
            outcome = Outcome.Done(CoapResponse(message.code, options, message.payload))
        }

        private suspend fun fail(reason: CoapException.Reason, diagnostic: String): Nothing {
            if (reason == CoapException.Reason.RESPONSE_TIMEOUT && !answered && mode == BodyMode.NON) {
                // §7.2: a body that never got a response probes no faster than PROBING_RATE, and the peer
                // may no longer take Q-Block.
                keepsLease = true
                support.invalidate(peer)
            }
            logger.debug { "Q-Block transfer failed: $diagnostic" }
            throw CoapException(diagnostic, reason)
        }
    }

    companion object {
        /** The critical options of a Q-Block exchange: Block1 and Block2, so a mix is seen, and Q-Block1 and Q-Block2. */
        val RECOGNIZED_OPTIONS: Set<Int> = ClientExchange.BLOCKWISE_OPTIONS + setOf(CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number)

        /** Options with which the application manages blocks itself. */
        private val OWN_BLOCK_OPTIONS = listOf(
            CoapOptions.BLOCK1.number, CoapOptions.BLOCK2.number, CoapOptions.Q_BLOCK1.number, CoapOptions.Q_BLOCK2.number,
        )

        /** Methods that may be sent again (RFC 7252 §5.1, RFC 8132 §2, §3). */
        private val REPEATABLE = setOf(CoapCode.GET, CoapCode.FETCH, CoapCode.PUT, CoapCode.DELETE, CoapCode.IPATCH)

        /** Methods with a body whose last block may be sent again: PUT, FETCH and iPATCH. */
        private val REPEATABLE_WITH_BODY = setOf(CoapCode.PUT, CoapCode.FETCH, CoapCode.IPATCH)

        /** How many Continues and requests for missing blocks keep their tokens; older ones are forgotten. */
        const val MAX_OPEN_FOLLOW_UPS = 16

        private const val TAG_LENGTH = 4
        private const val MAX_ETAG_LENGTH = 8
        private const val MAX_EXPONENT = 6
    }
}

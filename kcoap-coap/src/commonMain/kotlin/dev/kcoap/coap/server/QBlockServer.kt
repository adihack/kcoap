package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.ContentFormat
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.BodyMode
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.EndpointEvent
import dev.kcoap.coap.stack.EndpointEvent.LimitReached.Limit
import dev.kcoap.coap.stack.ExpiringCache
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.coap.stack.MessageLimits
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.QBlock1Receiver
import dev.kcoap.coap.stack.QBlock2Sender
import dev.kcoap.coap.stack.QBlockAction
import dev.kcoap.coap.stack.QBlockCheck
import dev.kcoap.coap.stack.QBlockTiming
import dev.kcoap.coap.stack.TransmissionOutcome
import dev.kcoap.coap.stack.TransmissionOwner
import dev.kcoap.log.LogTags
import dev.kcoap.transport.Endpoint
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The server side of Q-Block (RFC 9177) for a [CoapServer] with [QBlockServerConfig.isEnabled]: it drives the
 * sans-IO state machines [QBlock1Receiver] and [QBlock2Sender] (ADR 0006), which decide every block and answer,
 * and adds what they leave to a driver: the options of each message, message security, timers, bounds and
 * the address check before bursts.
 *
 * - **Admission** ([accepts], [answer]). Q-Block runs only for the requests [QBlockServerConfig] lets it run
 *   for; [CoapServer] treats the options as unrecognized for every other request (§4.1). [QBlockCheck] refuses
 *   requests that break the structural rules: 4.02 for Q-Block mixed with Block1 or Block2, 4.00 for a missing
 *   Request-Tag or Size1, a payload that does not fill its block, SZX 7 without BERT or Q-Block2 options out
 *   of order, 4.13 with Size1 above [CoapServerConfig.maxBodySize].
 * - **Q-Block1 uploads** (§4.3). The blocks of a body, one operation by [OperationKey] (Request-Tag included,
 *   Size1, Echo and the Q-Block options not), go to its [PartialBody]. Its receiver answers each block: no
 *   response in the middle of a set, 2.31 with Q-Block1 when a Non-confirmable set is complete, a
 *   Non-confirmable 4.08 with Content-Format 272 listing the missing blocks, at once when a later set arrives
 *   while earlier ones have gaps and on the NON_RECEIVE_TIMEOUT ladder otherwise. The request that completes
 *   the body runs the handler with the whole body and gets the final response; the blocks of a burst are
 *   handled concurrently, so on a multi-threaded dispatcher that need not be the last one to arrive, which
 *   §4.3 allows (any token of the body, preferably the last). A Reset to a 4.08 discards
 *   the body. The final response is remembered for NON_PARTIAL_TIMEOUT, so that a block sent again gets it
 *   again (§4.3: a duplicate is answered as the first was).
 * - **Q-Block2 bodies** (§4.4, §4.7). A 2.01, 2.02, 2.04 or 2.05 with a payload, answering a request that
 *   carried Q-Block1 or Q-Block2, goes out as a [ServedBody], even when it fits one block: Non-confirmable
 *   sets of MAX_PAYLOADS blocks on the request, NON_TIMEOUT_RANDOM apart unless a Continue asks for the next
 *   set; Confirmable blocks one after the other as each is acknowledged, the first piggybacked; on a
 *   connection all at once. The body stays for its Continues and requests of missing blocks. A GET or FETCH
 *   asking for `0/M=1` runs the handler again. For other methods only a request without payload continues a
 *   cached body: a Continue, a request of missing blocks (4.08 when nothing is cached), or `0/M=1` with the
 *   body's Request-Tag. Every other request of theirs is a new one whose handler runs, also one with a
 *   payload and Q-Block2 `0/M=1` (§4.1: how a client offers Q-Block for the response to a payload); a
 *   payload next to a later Q-Block2 block gets 4.00.
 * - **Amplification** (RFC 9175 §2.4 item 3). Before a burst to a client whose address
 *   [CoapServer.mustVerifyAddress] wants verified, the request gets a 4.01 with Echo instead; libcoap's probe,
 *   a single 16-byte block, stays within the factor. What counts is everything sent in answer to one
 *   request: its first set and every later set on the timer, a separate Confirmable block with all its
 *   retransmissions. Sets that would take it beyond the factor stop; a Continue is a request of its own and
 *   is checked, or challenged, for its set.
 * - **Observe** (RFC 7641, RFC 9177 §4.5). A GET or FETCH registration with Q-Block2, or a FETCH one uploaded
 *   with Q-Block1, adds an observer whose notifications are Q-Block2 bodies ([notify]): each change gives one
 *   body with an ETag of its own and the next Observe value on every block, all of them Confirmable or all
 *   Non-confirmable, on the registration's token (the token of the request that completed a Q-Block1
 *   registration, Figure 14). The registration response carries the Observe value on every block too. Blocks
 *   sent for a Continue carry it; those that answer a request for missing blocks do not. A body is the
 *   observation's one outstanding notification until every block went out (Confirmable ones acknowledged),
 *   and a Non-confirmable one, which holds one NSTART slot towards the client ([NotificationBody.lease]), for
 *   at least [CoapServerConfig.nonConfirmableNotificationInterval]; changes meanwhile fold into the next body,
 *   which ends whatever the older one still had to send. A Reset to any block of a body, or a Confirmable
 *   block that is not acknowledged, removes the observer (RFC 7641 §3.6, §4.5). Notification bodies are not
 *   checked against the amplification factor: their client verified its address if its registration needed
 *   to, and RFC 7641 §4.5 has Confirmable notifications confirm that it still wants them.
 * - **Teardown** ([forget], [close]). A peer's session or connection ending drops its bodies and timers.
 *
 * Bodies are bounded by [QBlockServerConfig]. Room in the cache is made from the bodies done sending first;
 * one that leaves it while still sending, to a newer body of its operation or because every other one is
 * sending too, finishes its sets without being found by follow-ups (at most as many as the cache holds). A
 * GET or FETCH body of one block is not cached at all. Every body with a deadline has one timer coroutine,
 * and one more expires the caches, so after NON_PARTIAL_TIMEOUT without traffic nothing is left. All state is
 * under one [Mutex], never held across a suspension; messages go out after it is released.
 */
internal class QBlockServer(
    private val server: CoapServer,
    private val endpoint: CoapEndpoint,
    private val config: CoapServerConfig,
    parent: Job,
) {
    private val qBlock = config.qBlock
    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_SERVER)
    private val timing = QBlockTiming(endpoint.parameters)
    private val reserve = if (config.security != null) config.securityReserve else 0

    /**
     * Seeded once from the endpoint's source, so tests stay reproducible, and used under [lock] only. A server
     * with Q-Block off draws nothing, so the endpoint's sequence of random values stays as it was.
     */
    private val random = if (qBlock.isEnabled) Random(endpoint.config.random.nextLong()) else Random(0)
    private val scope = CoroutineScope(endpoint.scope.coroutineContext + SupervisorJob(parent) + CoroutineName("coap-qblock"))

    private val lock = Mutex()
    private val partials = LinkedHashMap<OperationKey, PartialBody>()
    private val served = ExpiringCache<OperationKey, ServedBody>(qBlock.maxCachedBodies, timing.nonPartialTimeout)

    /**
     * Bodies that left [served] while they were still sending, to a newer body of their operation or for
     * room: no request finds them any more, but their client gets the rest of the sets it is waiting for. At
     * most [QBlockServerConfig.maxCachedBodies], the oldest stopping for a new one; each goes once it is done.
     */
    private val sending = LinkedHashSet<ServedBody>()
    private val completed = ExpiringCache<OperationKey, CompletedBody>(qBlock.maxCachedBodies, timing.nonPartialTimeout)
    private var nextETag = random.nextInt()
    private var sweeper: Job? = null

    /**
     * A Q-Block1 body that was completed: the final [response] (null while the handler still runs), the
     * number of the block that completed it, and the ETag of the Q-Block2 body that carried the response, if
     * one did.
     */
    private class CompletedBody(val completingNumber: Long) {
        var response: CoapResponse? = null
        var etag: ByteArray? = null
    }

    /** Whether Q-Block may run for [inbound]: it is on, and allowed with the request's security and transport. */
    fun accepts(inbound: InboundRequest, unprotected: UnprotectedRequest?): Boolean =
        qBlock.isEnabled && (unprotected == null || qBlock.withMessageSecurity) &&
            (inbound.connection == null || qBlock.onReliableTransports)

    /**
     * The reply to [ref], a request that carries Q-Block1 or Q-Block2 and that [accepts], for [resource]; null
     * when it was rejected without one (RFC 7252 §5.4.1).
     */
    suspend fun answer(ref: QBlockRequest, resource: CoapResource, key: OperationKey, limits: MessageLimits): Reply? {
        val violation = QBlockCheck.request(ref.request, limits.allowsBert, config.maxBodySize)
        if (violation != null) {
            log.debug { "Refused a Q-Block request from ${ref.inbound.peer}: $violation" }
            if (violation.resetIfNonConfirmable && !ref.inbound.isConfirmable) {
                ref.inbound.reject(violation.diagnostic)
                return null
            }
            val refusal = CoapResponse.error(violation.code, violation.diagnostic)
            return Reply.Single(violation.size1?.let { size1 -> refusal.withOptions { size1(size1) } } ?: refusal)
        }
        return if (ref.request.options.qBlock1 != null) receive(ref, resource, key, limits) else download(ref, resource, key, limits)
    }

    /**
     * [response], held back from [ref]'s request until the client verified its address (RFC 9175 §2.4 item 3),
     * now that its repeat with Echo came: as a Q-Block2 body when it is one.
     */
    suspend fun serve(ref: QBlockRequest, response: CoapResponse, key: OperationKey, limits: MessageLimits): Reply =
        shape(ref, response, key, limits)

    // ---------------------------------------------------------------- Q-Block1 (RFC 9177 §4.3)

    private sealed interface Upload {
        /** A block of a completed body that gets the final response again. */
        class Repeat(val record: CompletedBody) : Upload

        /** A block of a completed body that gets nothing, as the first copy did. */
        data object Duplicate : Upload

        /** A new body beyond [limit]. */
        class Refused(val limit: Limit) : Upload

        /**
         * What [body]'s receiver answers; [record] when the block completed the body, and [evicted], the peer
         * whose record of a completed body gave way to it.
         */
        class Next(
            val body: PartialBody,
            val action: QBlockAction<QBlockRequest>,
            val record: CompletedBody? = null,
            val evicted: Endpoint? = null,
        ) : Upload
    }

    private suspend fun receive(ref: QBlockRequest, resource: CoapResource, key: OperationKey, limits: MessageLimits): Reply {
        val block = checkNotNull(ref.request.options.qBlock1)
        val now = endpoint.now()
        return when (val upload = lock.withLock { receive(ref, key, block, now) }) {
            is Upload.Repeat -> repeat(ref, key, upload.record, limits)
            Upload.Duplicate -> Reply.None
            is Upload.Refused -> refuse(ref.inbound.peer, upload.limit)
            is Upload.Next -> {
                upload.evicted?.let { endpoint.report(EndpointEvent.LimitReached(it, Limit.CACHED_BODIES)) }
                next(ref, resource, upload, limits)
            }
        }
    }

    /** Under [lock]: hands the block to its body, starting one if it is the first. */
    private fun receive(ref: QBlockRequest, key: OperationKey, block: BlockOption, now: Duration): Upload {
        completed.get(key, now)?.let { record ->
            val isFinal = !block.more || block.number == record.completingNumber
            return if (isFinal && record.response != null) Upload.Repeat(record) else Upload.Duplicate
        }
        var isFirst = false
        val body = partials[key] ?: run {
            val limit = when {
                partials.size >= qBlock.maxPartialBodies -> Limit.PARTIAL_BODIES
                partials.values.count { it.peer == key.peer } >= qBlock.maxBodiesPerPeer -> Limit.BODIES_PER_PEER
                else -> null
            }
            if (limit != null) return Upload.Refused(limit)
            val mode = BodyMode.of(ref.inbound.message.type, ref.inbound.connection != null)
            isFirst = true
            PartialBody(key, QBlock1Receiver(ref.request, mode, timing, now)).also { partials[key] = it }
        }
        val action = body.receiver.onBlock(now, ref.request, ref).single()
        if (isFirst && action is QBlockAction.Reject) {
            // Nothing of the body was taken, so nothing of it stays (plan row 39: refused before any state).
            removePartial(body)
            return Upload.Next(body, action)
        }
        if (!body.receiver.isComplete) {
            schedule(body)
            return Upload.Next(body, action)
        }
        removePartial(body)
        // From now on a copy of a block is a duplicate of a completed body, also while the handler runs.
        val record = CompletedBody(block.number)
        val evicted = completed.put(key, record, now)?.peer
        ensureSweeper()
        return Upload.Next(body, action, record, evicted)
    }

    private suspend fun next(ref: QBlockRequest, resource: CoapResource, upload: Upload.Next, limits: MessageLimits): Reply {
        val body = upload.body
        return when (val action = upload.action) {
            is QBlockAction.NoResponse -> Reply.None
            is QBlockAction.Send231 -> Reply.Single(
                CoapResponse(CoapCode.CONTINUE, OptionSet.build { qBlock1(BlockOption(action.num, true, body.receiver.sizeExponent)) }),
            )
            is QBlockAction.Send408 -> {
                endpoint.report(EndpointEvent.QBlockRecovery(body.peer, isRequestedByPeer = false, blocks = action.missing))
                Reply.Single(missingBlocks(action.missing), owner = resetOwner(body))
            }
            is QBlockAction.Reject -> Reply.Single(CoapResponse.error(action.code, action.diagnostic))
            is QBlockAction.Deliver -> complete(ref, resource, body.key, action.message, checkNotNull(upload.record), limits)
            else -> {
                log.debug { "Ignored a block from ${body.peer}: $action" }
                Reply.None
            }
        }
    }

    /** The body is whole: the handler runs on [assembled], and its response goes on the completing request. */
    private suspend fun complete(
        ref: QBlockRequest,
        resource: CoapResource,
        key: OperationKey,
        assembled: CoapMessage,
        record: CompletedBody,
        limits: MessageLimits,
    ): Reply {
        log.debug { "Q-Block1 body of ${assembled.payloadSize} bytes from ${ref.inbound.peer} is complete" }
        val answered = try {
            // A FETCH registration is uploaded with Observe on every block; its token is the completing one's (Figure 14).
            server.respondTo(resource, assembled, ref.inbound, ref.unprotected, key, requestedBlockSize(ref))
        } catch (e: Throwable) {
            lock.withLock { if (completed.peek(key, endpoint.now()) === record) completed.remove(key) }
            throw e
        }
        val response = answered.response
        val reply = shape(ref, response, key, limits, answered.observation)
        lock.withLock {
            record.response = response
            record.etag = (reply as? Reply.Body)?.body?.etag
        }
        return reply
    }

    /** A block of a completed body that asks for the final response again (§4.3). */
    private suspend fun repeat(ref: QBlockRequest, key: OperationKey, record: CompletedBody, limits: MessageLimits): Reply {
        val response = checkNotNull(record.response)
        val etag = record.etag
        if (etag != null) {
            val now = endpoint.now()
            val again = lock.withLock {
                val body = served.get(key, now)?.takeIf { !it.isDropped && it.etag.contentEquals(etag) } ?: return@withLock null
                Reply.Body(body, body.sender.restart(now, ref).also { schedule(body) }, isNew = false)
            }
            if (again != null) return again
        }
        return shape(ref, response, key, limits)
    }

    private fun refuse(peer: Endpoint, limit: Limit): Reply {
        endpoint.report(EndpointEvent.LimitReached(peer, limit))
        log.debug { "Refused a new Q-Block1 body from $peer: $limit" }
        // RFC 7252 §5.9.3.4: Max-Age says when to try again; a body ends at the latest when its ladder does.
        val retry = ceil(timing.nonReceiveTimeout.toDouble(DurationUnit.SECONDS)).toLong()
        return Reply.Single(CoapResponse.error(CoapCode.SERVICE_UNAVAILABLE, "Too many Q-Block bodies").withOptions { maxAge(retry) })
    }

    /** RFC 9177 §5: a 4.08 whose Content-Format 272 payload lists [missing]. */
    private fun missingBlocks(missing: List<Long>): CoapResponse = CoapResponse(
        CoapCode.REQUEST_ENTITY_INCOMPLETE,
        OptionSet.build { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) },
        MissingBlocks.encode(missing),
    )

    /** §4.3: a Reset to a 4.08 shows the client abandoned the body, which is then discarded. */
    private fun resetOwner(body: PartialBody) = TransmissionOwner { _, outcome ->
        if (outcome == TransmissionOutcome.Reset) {
            launch("discard a Q-Block1 body after a Reset") {
                val actions = lock.withLock {
                    if (body.isDropped) return@withLock emptyList()
                    body.receiver.onReset(endpoint.now()).also { if (body.receiver.isDiscarded) removePartial(body) }
                }
                executeUpload(body, actions)
            }
        }
    }

    private suspend fun onPartialTimer(body: PartialBody, at: Duration) {
        val actions = lock.withLock {
            if (body.isDropped || body.timerAt != at) return
            body.timer = null
            body.timerAt = null
            val next = try {
                body.receiver.onTimer(endpoint.now())
            } catch (e: Exception) {
                // A body whose timer cannot run would stay for good: it goes, and the failure is logged.
                removePartial(body)
                throw e
            }
            next.also { if (body.receiver.isDiscarded) removePartial(body) else schedule(body) }
        }
        executeUpload(body, actions)
    }

    /** What an upload's timer or a Reset asks for: a 4.08 on a request the client still holds, or the end. */
    private suspend fun executeUpload(body: PartialBody, actions: List<QBlockAction<QBlockRequest>>) {
        for (action in actions) {
            when (action) {
                is QBlockAction.Send408 -> {
                    endpoint.report(EndpointEvent.QBlockRecovery(body.peer, isRequestedByPeer = false, blocks = action.missing))
                    respond(action.ref, missingBlocks(action.missing).toMessage(action.ref.token), resetOwner(body))
                }
                is QBlockAction.Discard -> {
                    log.debug { "Dropped a partial Q-Block1 body from ${body.peer}: ${action.reason}" }
                    endpoint.report(EndpointEvent.PartialBodyDropped(body.peer, action.reason))
                }
                else -> log.debug { "Ignored $action of $body" }
            }
        }
    }

    /** Under [lock]. */
    private fun removePartial(body: PartialBody) {
        if (partials[body.key] === body) partials.remove(body.key)
        body.drop()
    }

    // ---------------------------------------------------------------- Q-Block2 (RFC 9177 §4.4)

    private sealed interface Lookup {
        class Found(val body: ServedBody) : Lookup

        data object Missing : Lookup

        /** A follow-up without Request-Tag that fits more than one body (Figure 12). */
        data object Ambiguous : Lookup
    }

    private suspend fun download(ref: QBlockRequest, resource: CoapResource, key: OperationKey, limits: MessageLimits): Reply {
        val request = ref.request
        val single = request.options.qBlock2List.singleOrNull()
        val isWholeBody = single != null && single.number == 0L && single.more
        val isSafe = isSafe(request.code)
        val hasPayload = request.payloadSize > 0
        if (!isSafe && hasPayload && single?.number != 0L) {
            // §4.1, §4.4: a payload makes it a new request, which asks for the start of its response body.
            return Reply.Single(CoapResponse.error(CoapCode.BAD_REQUEST, "A request with a payload asks for Q-Block2 block 0"))
        }
        val isFollowUp = when {
            // §4.4: 0/M=1 runs a GET or FETCH again; everything else of theirs continues the cached body.
            isSafe -> !isWholeBody
            // A payload is a new request of an unsafe method, never a follow-up (§4.4, §4.7: those carry none).
            hasPayload -> false
            // 0/M=1 asks for an unsafe method's body again from the cache only with that body's Request-Tag.
            isWholeBody -> request.options.requestTags.isNotEmpty()
            else -> true
        }
        if (isFollowUp) {
            val now = endpoint.now()
            val reply = lock.withLock {
                when (val lookup = find(key, now, untagged = !hasPayload)) {
                    is Lookup.Found -> {
                        val body = lookup.body
                        Reply.Body(body, body.sender.onRequest(now, request, ref).also { schedule(body) }, isNew = false)
                    }
                    Lookup.Ambiguous -> Reply.Single(
                        CoapResponse.error(CoapCode.BAD_REQUEST, "Q-Block2 request fits more than one body: add its Request-Tag"),
                    )
                    // §4.4: a body that is no longer cached cannot be continued without running an unsafe method
                    // again; an unsafe 0/M=1 whose body is gone is a new request.
                    Lookup.Missing -> if (isSafe || isWholeBody) {
                        null
                    } else {
                        Reply.Single(CoapResponse.error(CoapCode.REQUEST_ENTITY_INCOMPLETE, "No response body to continue"))
                    }
                }
            }
            if (reply is Reply.Body) reportRecovery(ref, reply)
            if (reply != null) return reply
        }
        // §4.4: a GET or FETCH runs again, also for a body that is no longer cached, which then has a new ETag;
        // a new request runs, and its body replaces the one cached for the same operation. A registration
        // adds its observer here (RFC 7641 §4.1).
        val answered = server.respondTo(resource, request, ref.inbound, ref.unprotected, key, requestedBlockSize(ref))
        return shape(ref, answered.response, key, limits, answered.observation)
    }

    /**
     * The block size the client of [ref] takes: that of its Q-Block2, else of its own Q-Block1 blocks, which
     * shows what it can take, else [CoapServerConfig.maxBlockSize]; at most that. SZX 7 is never sent: a BERT
     * request is answered in 1024-byte blocks.
     */
    private fun requestedBlockSize(ref: QBlockRequest): Int {
        val options = ref.request.options
        val requested = (options.qBlock2 ?: options.qBlock1)?.takeIf { !it.isBert }?.size ?: config.maxBlockSize
        return minOf(requested, config.maxBlockSize)
    }

    /** RFC 7252 §5.1, RFC 8132 §2: the methods whose handler may run again for a body that is gone. */
    private fun isSafe(code: CoapCode): Boolean = code == CoapCode.GET || code == CoapCode.FETCH

    private fun reportRecovery(ref: QBlockRequest, reply: Reply.Body) {
        val single = ref.request.options.qBlock2List.singleOrNull()
        if (single != null && single.more && (single.number == 0L || timing.sets.isContinue(single.number))) return
        val resent = reply.actions.filterIsInstance<QBlockAction.SendBlock<QBlockTarget>>().filter { it.ref === ref }.map { it.num }
        if (resent.isNotEmpty()) endpoint.report(EndpointEvent.QBlockRecovery(ref.inbound.peer, isRequestedByPeer = true, blocks = resent))
    }

    /**
     * Under [lock]: the body a follow-up request with [key] continues. §4.7 (Figure 12) lets a follow-up of a
     * Q-Block1 upload, which carries no payload, leave its Request-Tag out: with [untagged], such a request
     * continues the one body it fits, and is refused when it fits more than one.
     */
    private fun find(key: OperationKey, now: Duration, untagged: Boolean): Lookup {
        served.prune(now).forEach { it.drop() }
        served.get(key, now)?.let { return Lookup.Found(it) }
        val requestTag = CoapOptions.REQUEST_TAG.number
        if (!untagged || key.options.any { it.number == requestTag }) return Lookup.Missing
        val matches = served.entryList.filter { (candidate, _) ->
            candidate.peer == key.peer && candidate.identity == key.identity && candidate.method == key.method &&
                candidate.options.filter { it.number != requestTag } == key.options
        }
        return when (matches.size) {
            0 -> Lookup.Missing
            1 -> served.get(matches.single().first, now)?.let { Lookup.Found(it) } ?: Lookup.Missing
            else -> Lookup.Ambiguous
        }
    }

    /**
     * [response] to [ref], which carried Q-Block1 or Q-Block2: a [ServedBody] when it is a 2.01, 2.02, 2.04 or
     * 2.05 with a payload (§4.4, §4.7), otherwise the response as it is. [observation] is the observer the
     * request registered, whose first notification the response is (RFC 7641 §4.1).
     */
    private suspend fun shape(
        ref: QBlockRequest,
        response: CoapResponse,
        key: OperationKey,
        limits: MessageLimits,
        observation: ServerObservation? = null,
    ): Reply {
        if (!isBody(response)) return Reply.Single(response, observation)
        val size = blockSize(response, limits, requestedBlockSize(ref)) ?: run {
            // An error ends the client's observation (RFC 7641 §3.2), so the server keeps no observer either.
            observation?.let { server.observations.remove(it, "registration response 4.13") }
            return Reply.Single(CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE, "The response options leave no room for a block"))
        }
        val mode = BodyMode.of(ref.inbound.message.type, ref.inbound.connection != null)
        val now = endpoint.now()
        val evicted = ArrayList<Pair<Endpoint, Limit>>()
        val reply = lock.withLock {
            val sender = QBlock2Sender<QBlockTarget>(response.payloadSize, size, mode, timing, timing.drawNonTimeoutRandom(random))
            val body = ServedBody(key, response, nextETag(), sender, now, observation)
            observation?.let { follow(body, it) }
            // A GET or FETCH body of one block is not kept: asking for it again runs the handler, as cheaply.
            if (!isSafe(ref.request.code) || sender.count > 1) store(body, now, evicted)
            Reply.Body(body, first(body, ref, now).also { schedule(body) }, isNew = true)
        }
        evicted.forEach { (peer, limit) -> endpoint.report(EndpointEvent.LimitReached(peer, limit)) }
        log.debug { "${response.code} of ${response.payloadSize} bytes to ${ref.inbound.peer} in ${reply.body.sender.count} Q-Block2 blocks of $size" }
        return reply
    }

    private fun isBody(response: CoapResponse): Boolean =
        response.code in BODY_CODES && response.payloadSize > 0 &&
            CoapOptions.BLOCK1.number !in response.options && CoapOptions.BLOCK2.number !in response.options

    /**
     * The largest block of at most [limit] bytes that fits a message within [limits] next to the response's
     * options, a 4-byte ETag, Q-Block2, Size2, Observe, an 8-byte token and the security reserve; null when not
     * even 16 bytes fit.
     */
    private fun blockSize(response: CoapResponse, limits: MessageLimits, limit: Int): Int? {
        val header = response.withOptions {
            remove(CoapOptions.ETAG.number)
            etag(ByteArray(ETAG_LENGTH))
            qBlock2(BlockOption(BlockOption.MAX_NUMBER, true, 6))
            size2(response.payloadSize.toLong())
            observe(MAX_OBSERVE)
        }
        return Blockwise.fittingBlockSize(limits, header.toMessage(ByteArray(CoapMessage.MAX_TOKEN_LENGTH)), reserve, limit)
    }

    /** Under [lock]: the first blocks of a new body, as [ref]'s Q-Block2 options ask (§4.4). */
    private fun first(body: ServedBody, ref: QBlockRequest, now: Duration): List<QBlockAction<QBlockTarget>> {
        val options = ref.request.options.qBlock2List
        val single = options.singleOrNull()
        val whole = options.isEmpty() || options.any { it.sizeExponent != body.sender.sizeExponent } ||
            (single != null && single.more && (single.number == 0L || timing.sets.isContinue(single.number)))
        return if (whole) body.sender.start(now, ref) else body.sender.onRequest(now, ref.request, ref)
    }

    /**
     * Under [lock]: caches [body] in place of an older one for its operation, within the bounds. Room is made
     * from the bodies used longest ago that are done sending first; none leaving the cache is cut off while
     * it still sends ([retire]).
     */
    private fun store(body: ServedBody, now: Duration, evicted: MutableList<Pair<Endpoint, Limit>>) {
        served.prune(now).forEach { it.drop() }
        served.remove(body.key)?.let { old ->
            // RFC 9177 §4.4: the client gives up an older body of its observation once a newer one comes.
            if (old.observation != null && old.observation === body.observation) end(old, now)
            retire(old, evicted)
        }
        val own = served.entryList.filter { it.first.peer == body.peer }
        if (own.size >= qBlock.maxBodiesPerPeer) {
            retire(checkNotNull(served.remove(idlest(own).key)), evicted)
            evicted += body.peer to Limit.BODIES_PER_PEER
        }
        if (served.size >= qBlock.maxCachedBodies) {
            val victim = checkNotNull(served.remove(idlest(served.entryList).key))
            retire(victim, evicted)
            evicted += victim.peer to Limit.CACHED_BODIES
        }
        served.put(body.key, body, now)
        ensureSweeper()
    }

    /** The body used longest ago among [entries] that is done sending, else the one used longest ago. */
    private fun idlest(entries: List<Pair<OperationKey, ServedBody>>): ServedBody =
        (entries.firstOrNull { !it.second.sender.isSending } ?: entries.first()).second

    /**
     * Under [lock]: [body] has left [served]. One that is done sending goes; one still sending finishes its
     * sets from [sending], where the oldest gives way when it is full.
     */
    private fun retire(body: ServedBody, evicted: MutableList<Pair<Endpoint, Limit>>) {
        if (!body.sender.isSending) {
            body.drop()
            return
        }
        if (sending.size >= qBlock.maxCachedBodies) {
            val oldest = sending.first()
            sending.remove(oldest)
            oldest.drop()
            evicted += oldest.peer to Limit.CACHED_BODIES
        }
        sending += body
    }

    /** Under [lock]: a body of [sending] goes once it has nothing left to send. */
    private fun settle(body: ServedBody) {
        if (!body.sender.isSending && sending.remove(body)) body.drop()
    }

    /** Under [lock]: §4.4 wants an ETag unique for every transmitted body; 4 bytes from a counter with a random start. */
    private fun nextETag(): ByteArray {
        val value = nextETag++
        return ByteArray(ETAG_LENGTH) { (value ushr (24 - 8 * it)).toByte() }
    }

    /**
     * Sends the blocks of [reply], the answer to [trigger], unless they are a burst that must wait for the
     * client's address to be verified (RFC 9175 §2.4 item 3): then nothing goes out, a new body is forgotten,
     * and the returned 4.01 with Echo goes instead. A burst counts against [trigger] alone, also a Continue's,
     * which is a request of its own; blocks on [trigger]'s own request are recorded there for its later sets.
     */
    suspend fun send(trigger: QBlockRequest, reply: Reply.Body): Reply? {
        val body = reply.body
        val blocks = reply.actions.filterIsInstance<QBlockAction.SendBlock<QBlockTarget>>()
        var bytes = 0L
        val mustVerify = blocks.isNotEmpty() &&
            server.mustVerifyAddress(trigger.inbound, trigger.request, trigger.unprotected, isBurst = true) {
                wireBytes(body, blocks).also { bytes = it }
            }
        if (mustVerify) {
            if (reply.isNew) {
                lock.withLock {
                    if (served.peek(body.key, endpoint.now()) === body) served.remove(body.key)
                    body.drop()
                }
                // A registration whose response is not sent registers nothing (RFC 7641 §4.1).
                body.observation?.let { server.observations.remove(it, "address not verified") }
            }
            val kept = if (reply.isNew && AddressVerification.isRepeatSensitive(trigger.request.code)) {
                AddressVerification.Withheld(trigger.request, body.response)
            } else {
                null
            }
            return server.challenge(trigger.inbound.peer, endpoint.now(), kept)
        }
        // One reply's blocks all go on one request: the trigger's own, or the original one of a Continue.
        val own = (blocks.firstOrNull()?.ref as? QBlockRequest)?.takeIf { it.inbound === trigger.inbound }
        if (own != null && bytes > 0) lock.withLock { own.sentBytes += bytes }
        execute(body, reply.actions)
        // A request the body did not answer, such as a Continue whose set went out before, gets no response.
        if (!trigger.inbound.isAnswered) trigger.inbound.finishWithoutResponse()
        settleIfDone(body)
        return null
    }

    private suspend fun execute(body: ServedBody, actions: List<QBlockAction<QBlockTarget>>) {
        for (action in actions) {
            when (action) {
                is QBlockAction.SendBlock -> when (val target = action.ref) {
                    is QBlockRequest -> respond(
                        target,
                        body.block(action.num, action.more, target.token, body.observeFor(target)),
                        blockOwner(body, action.num),
                    )
                    is NotificationBody -> notifyBlock(body, target, action.num, action.more)
                }
                is QBlockAction.NoResponse -> (action.ref as? QBlockRequest)?.inbound?.finishWithoutResponse()
                is QBlockAction.Reject -> (action.ref as? QBlockRequest)?.let { ref ->
                    respond(ref, CoapResponse.error(action.code, action.diagnostic).toMessage(ref.token))
                }
                else -> log.debug { "Ignored $action of $body" }
            }
        }
    }

    /**
     * How a block went: an acknowledgement sends the next Confirmable one, a Reset or a failure ends the body. For
     * a body of an observation, every block is a notification (RFC 9177 §4.5): a Reset, a failure or a
     * Confirmable block that is not acknowledged removes the observer too (RFC 7641 §3.6, §4.5).
     */
    private fun blockOwner(body: ServedBody, num: Long) = TransmissionOwner { _, outcome ->
        val event: ((QBlock2Sender<QBlockTarget>, Duration) -> List<QBlockAction<QBlockTarget>>)? = when (outcome) {
            is TransmissionOutcome.Acknowledged -> { sender, now -> sender.onAcknowledged(now, num) }
            TransmissionOutcome.TimedOut -> { sender, now -> sender.onTimeout(now, num) }
            TransmissionOutcome.Reset, is TransmissionOutcome.Failed -> { sender, now ->
                log.debug { "Block $num of $body ended with $outcome: the body stops" }
                sender.onReset(now)
            }
            TransmissionOutcome.SlotReleased -> null
        }
        val removal = when (outcome) {
            TransmissionOutcome.Reset -> "reset by client"
            TransmissionOutcome.TimedOut -> "notification not acknowledged"
            is TransmissionOutcome.Failed -> outcome.cause.message ?: "failed"
            else -> null
        }
        if (event != null) {
            launch("continue a Q-Block2 body") {
                step(body, event)
                val observation = body.observation
                if (observation != null && removal != null) server.observations.remove(observation, removal)
            }
        }
    }

    private suspend fun step(body: ServedBody, event: (QBlock2Sender<QBlockTarget>, Duration) -> List<QBlockAction<QBlockTarget>>) {
        val actions = lock.withLock {
            if (body.isDropped) return
            val now = endpoint.now()
            keepAlive(body, now)
            event(body.sender, now).also {
                schedule(body)
                settle(body)
            }
        }
        execute(body, actions)
        settleIfDone(body)
    }

    private suspend fun onServedTimer(body: ServedBody, at: Duration) {
        val now = endpoint.now()
        val actions = lock.withLock {
            if (body.isDropped || body.timerAt != at) return
            body.timer = null
            body.timerAt = null
            keepAlive(body, now)
            body.sender.onTimer(now).also {
                schedule(body)
                settle(body)
            }
        }
        // A later set adds to what the request it answers has had, which must stay within the factor until the
        // address is verified (RFC 9175 §2.4 item 3); beyond, the sets stop, and a Continue is a request of its own.
        // A notification's sets answer no request (see the class comment).
        val blocks = actions.filterIsInstance<QBlockAction.SendBlock<QBlockTarget>>()
        val ref = blocks.firstOrNull()?.ref as? QBlockRequest
        if (ref != null) {
            val before = lock.withLock { ref.sentBytes }
            var bytes = 0L
            val mustVerify = server.mustVerifyAddress(ref.inbound, ref.request, ref.unprotected, isBurst = true) {
                bytes = wireBytes(body, blocks)
                before + bytes
            }
            if (mustVerify) {
                log.debug { "Stopped $body: its next set would take ${ref.inbound.peer} beyond the amplification factor" }
                lock.withLock {
                    body.sender.onReset(endpoint.now())
                    schedule(body)
                    settle(body)
                }
                settleIfDone(body)
                return
            }
            if (bytes > 0) lock.withLock { ref.sentBytes += bytes }
        }
        execute(body, actions)
        settleIfDone(body)
    }

    /**
     * The bytes [blocks] of [body] put on the wire, for the amplification limit, which counts every byte sent
     * to an address, as QUIC does (RFC 9175 §2.4 item 3, RFC 9000 §8.1): a block that goes as a separate
     * Confirmable response counts with the MAX_RETRANSMIT retransmissions an address that never acknowledges
     * gets. Only the first block on a Confirmable request not answered yet is piggybacked.
     */
    private fun wireBytes(body: ServedBody, blocks: List<QBlockAction.SendBlock<QBlockTarget>>): Long {
        val piggybacked = HashSet<InboundRequest>()
        val copies = 1L + endpoint.parameters.maxRetransmit
        return blocks.sumOf { action ->
            val target = action.ref
            val size = UdpMessageCodec.encode(body.block(action.num, action.more, target.token, body.observeFor(target))).size.toLong()
            val inbound = (target as? QBlockRequest)?.inbound ?: return@sumOf size
            val isConfirmable = inbound.connection == null && inbound.message.type == CoapMessageType.CON
            if (isConfirmable && (inbound.isAnswered || !piggybacked.add(inbound))) size * copies else size
        }
    }

    /** Under [lock]: a body that is still sending stays cached as long. */
    private fun keepAlive(body: ServedBody, now: Duration) {
        if (served.peek(body.key, now) === body) served.get(body.key, now)
    }

    // ---------------------------------------------------------------- Observe (RFC 7641, RFC 9177 §4.5)

    /**
     * [response], a notification of [observation] of [type] (RFC 7641 §4.2), as a Q-Block2 body on the
     * observation's token (RFC 9177 §4.5). Returns null once its first blocks went out; otherwise the response
     * to send as one message: the response itself when it is no body, or a 4.13 when not even a block of 16
     * bytes fits next to its options. Its later sets go out NON_TIMEOUT_RANDOM apart or on Continues, and
     * requests for its missing blocks find it under the registration's operation, where it replaces the
     * observation's older body and ends whatever that still had to send (§4.4). A Non-confirmable body first
     * takes the lease of one NSTART slot towards the client, which may wait (RFC 7641 §4.5.1, RFC 9177 §7.2).
     */
    suspend fun notify(
        observation: ServerObservation,
        type: CoapMessageType,
        response: CoapResponse,
        limits: MessageLimits,
    ): CoapResponse? {
        if (!isBody(response)) return response
        val requested = checkNotNull(observation.qBlockSize) { "$observation was not registered with Q-Block" }
        val size = blockSize(response, limits, requested)
            ?: return CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE, "The notification options leave no room for a block")
        val mode = BodyMode.of(type, observation.connection != null)
        val nonTimeoutRandom = lock.withLock { timing.drawNonTimeoutRandom(random) }
        val lease = if (mode == BodyMode.NON) endpoint.leaseBody(observation.peer, response.payloadSize, nonTimeoutRandom) else null
        val target = NotificationBody(observation, type, lease)
        val evicted = ArrayList<Pair<Endpoint, Limit>>()
        val (body, actions) = try {
            lock.withLock {
                val now = endpoint.now()
                // No request to piggyback block 0 on: every block goes in a message of its own.
                val sender = QBlock2Sender<QBlockTarget>(
                    response.payloadSize, size, mode, timing, nonTimeoutRandom, firstBlockPiggybacked = false,
                )
                val body = ServedBody(observation.operationKey, response, nextETag(), sender, now, observation, target)
                observation.body?.let { older -> if (!older.isDropped) end(older, now) }
                follow(body, observation)
                // A body of one block is not kept: asking for it again runs the handler, as cheaply.
                if (sender.count > 1) store(body, now, evicted)
                body to sender.start(now, target).also { schedule(body) }
            }
        } catch (e: Throwable) {
            lease?.let { endpoint.releaseBody(it) }
            throw e
        }
        evicted.forEach { (peer, limit) -> endpoint.report(EndpointEvent.LimitReached(peer, limit)) }
        log.debug {
            "Notification of ${response.payloadSize} bytes to ${observation.peer} in ${body.sender.count} Q-Block2 blocks of $size, $type"
        }
        execute(body, actions)
        settleIfDone(body)
        return null
    }

    /** Sends block [num] of the notification [body] in a message of its own on the observation's token (RFC 7641 §4.2). */
    private suspend fun notifyBlock(body: ServedBody, target: NotificationBody, num: Long, more: Boolean) {
        val observation = target.observation
        try {
            val message = body.block(num, more, observation.token, body.observeFor(target))
            val outgoing = observation.unprotected?.protectResponse(message) ?: message
            endpoint.sendSeparately(observation.peer, outgoing, target.type, blockOwner(body, num), connection = observation.connection)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The security layer, its sequence-number store or the messaging layer failed: the observer goes.
            log.warn { "Could not send block $num of a notification to ${observation.peer}: ${e.message}" }
            stop(body)
            server.observations.remove(observation, "notification failed: ${e.message}")
        }
    }

    /**
     * Stops [body], the latest of an observation whose observer was removed (RFC 7641 §3.6): nothing more of it
     * goes out, and its lease is given back.
     */
    suspend fun stop(body: ServedBody) {
        lock.withLock {
            if (!body.isDropped) end(body, endpoint.now())
            body.markDone()
        }
    }

    /** Under [lock]: [body] sends nothing more by itself, but stays cached for what its client asks. */
    private fun end(body: ServedBody, now: Duration) {
        body.sender.onReset(now)
        schedule(body)
        settle(body)
    }

    /**
     * Under [lock]: [body] is now the outstanding notification of [observation], which learns when it is settled
     * ([ServedBody.isSettled]): then its lease goes back, and a change meanwhile sends the next body.
     */
    private fun follow(body: ServedBody, observation: ServerObservation) {
        body.onDone = { launch("settle a notification of ${observation.resource.path}") { settled(body, observation) } }
        observation.body = body
    }

    private suspend fun settled(body: ServedBody, observation: ServerObservation) {
        try {
            // A Non-confirmable body keeps its slot as long as a single Non-confirmable notification would.
            if (body.sender.mode == BodyMode.NON) delay(body.startedAt + config.nonConfirmableNotificationInterval - endpoint.now())
        } finally {
            withContext(NonCancellable) { body.notification?.lease?.let { endpoint.releaseBody(it) } }
        }
        observation.onBodySettled(body)
    }

    /** A body of an observation is done once every block went out (Confirmable ones acknowledged), or it stopped. */
    private suspend fun settleIfDone(body: ServedBody) {
        if (body.observation == null || body.isDone) return
        lock.withLock { if (body.sender.isStopped || body.sender.hasSentAll) body.markDone() }
    }

    // ---------------------------------------------------------------- timers and teardown

    /** Under [lock]. */
    private fun schedule(body: PartialBody) = arm(body, body.receiver.nextDeadline()) { onPartialTimer(body, it) }

    /** Under [lock]. */
    private fun schedule(body: ServedBody) = arm(body, body.sender.nextDeadline()) { onServedTimer(body, it) }

    /** Under [lock]: one timer coroutine per body, for its state machine's next deadline. */
    private fun arm(body: TimedBody, deadline: Duration?, fire: suspend (Duration) -> Unit) {
        if (body.isDropped || (deadline == body.timerAt && body.timer != null)) return
        body.timer?.cancel()
        body.timerAt = deadline
        body.timer = deadline?.let { at ->
            launch("run a Q-Block timer") {
                delay(at - endpoint.now())
                fire(at)
            }
        }
    }

    /** Under [lock]: one coroutine drops cached bodies and records once they expire, then ends. */
    private fun ensureSweeper() {
        if (sweeper == null) sweeper = launch("expire Q-Block bodies") { sweep() }
    }

    private suspend fun sweep() {
        while (true) {
            val next = lock.withLock {
                val now = endpoint.now()
                served.prune(now).forEach { it.drop() }
                completed.prune(now)
                listOfNotNull(served.earliestExpiry, completed.earliestExpiry).minOrNull().also { if (it == null) sweeper = null }
            } ?: return
            delay(next - endpoint.now())
        }
    }

    /** The session or connection of [peer] ended (RFC 7252 §9.1.1, RFC 8323 §7.4): its bodies and timers go. */
    suspend fun forget(peer: Endpoint) {
        val dropped = lock.withLock {
            val uploads = partials.values.filter { it.peer == peer }
            uploads.forEach { removePartial(it) }
            served.removeAll { it.peer == peer }.forEach { it.drop() }
            sending.filter { it.peer == peer }.forEach {
                sending.remove(it)
                it.drop()
            }
            completed.removeAll { it.peer == peer }
            if (served.size == 0 && completed.size == 0) {
                sweeper?.cancel()
                sweeper = null
            }
            uploads.size
        }
        repeat(dropped) { endpoint.report(EndpointEvent.PartialBodyDropped(peer, "the session ended")) }
    }

    /** The server stopped: every body and timer goes. */
    suspend fun close() {
        lock.withLock {
            partials.values.forEach { it.drop() }
            partials.clear()
            served.removeAll { true }.forEach { it.drop() }
            sending.forEach { it.drop() }
            sending.clear()
            completed.removeAll { true }
            sweeper?.cancel()
            sweeper = null
        }
    }

    /**
     * What the server holds, for tests: [partialBytes] is the payload the partial bodies hold, [sendingBodies]
     * the bodies that left the cache but still send.
     */
    class State(
        val partialBodies: Int,
        val partialBytes: Long,
        val servedBodies: Int,
        val sendingBodies: Int,
        val completedBodies: Int,
        val timers: Int,
    ) {
        val isEmpty: Boolean
            get() = partialBodies == 0 && servedBodies == 0 && sendingBodies == 0 && completedBodies == 0 && timers == 0

        override fun toString(): String =
            "State(partialBodies=$partialBodies, partialBytes=$partialBytes, servedBodies=$servedBodies, " +
                "sendingBodies=$sendingBodies, completedBodies=$completedBodies, timers=$timers)"
    }

    suspend fun state(): State = lock.withLock {
        val bodies = partials.values + served.entryList.map { it.second } + sending
        State(
            partials.size,
            partials.values.sumOf { it.receiver.heldBytes },
            served.size,
            sending.size,
            completed.size,
            bodies.count { it.timer?.isActive == true } + (if (sweeper?.isActive == true) 1 else 0),
        )
    }

    private suspend fun respond(ref: QBlockRequest, message: CoapMessage, owner: TransmissionOwner? = null) {
        try {
            ref.inbound.respond(ref.unprotected?.protectResponse(message) ?: message, owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn { "Could not send ${message.code} to ${ref.inbound.peer}: ${e.message}" }
        }
    }

    private fun launch(name: String, block: suspend () -> Unit): Job = scope.launch(CoroutineName(name)) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Q-Block: failed to $name" }
        }
    }

    companion object {
        /** Whether [options] carry Q-Block1 or Q-Block2 (RFC 9177 §4.2). */
        fun carriesQBlock(options: OptionSet): Boolean =
            CoapOptions.Q_BLOCK1.number in options || CoapOptions.Q_BLOCK2.number in options

        /** §4.4: the responses whose payload is a body sent with Q-Block2. */
        private val BODY_CODES = setOf(CoapCode.CREATED, CoapCode.DELETED, CoapCode.CHANGED, CoapCode.CONTENT)

        private const val ETAG_LENGTH = 4

        /** The largest Observe value, 3 bytes, which every block of a body of an observation carries (§4.5). */
        private const val MAX_OBSERVE = 0xFFFFFFL
    }
}

package dev.kcoap.coap

import dev.kcoap.coap.codec.UdpMessageCodec
import dev.kcoap.coap.stack.BodyMode
import dev.kcoap.coap.stack.MissingBlocks
import dev.kcoap.coap.stack.QBlock1Receiver
import dev.kcoap.coap.stack.QBlock2Sender
import dev.kcoap.coap.stack.QBlockAction
import dev.kcoap.coap.stack.QBlockCheck
import dev.kcoap.coap.stack.QBlockTiming
import dev.kcoap.coap.stack.TransmissionParameters
import dev.kcoap.common.toHex
import dev.kcoap.testkit.InMemoryTransport
import dev.kcoap.transport.DatagramEvent
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * A Q-Block server (RFC 9177) for client tests, scripted from the server cores of `dev.kcoap.coap.stack`
 * ([QBlock1Receiver], [QBlock2Sender]) over a raw [InMemoryTransport] on virtual time, so the client can be
 * tested against a server that supports Q-Block before kcoap's own server does.
 *
 * It keeps [resources] by path: GET serves one (Q-Block2 when the request carries Q-Block2 or came in
 * Q-Block1 blocks), PUT and POST store the body and answer 2.04, FETCH answers with [fetch] of the body,
 * DELETE removes it. `GET /.well-known/core` with Q-Block2 0/0/16, the probe, gets block 0 of 16 bytes with
 * Q-Block2 0/M/16, as libcoap answers it. Partial uploads are kept per peer and Request-Tag, so a client
 * that changes its address starts a new partial body; completed ones are remembered for NON_PARTIAL_TIMEOUT
 * so a block sent again is answered with the final response again (§4.3).
 *
 * Confirmable requests are acknowledged, piggybacked or empty, and their duplicates answered from a record;
 * Confirmable response blocks are sent once and their ACKs fed to [QBlock2Sender.onAcknowledged]. Without
 * [supportsQBlock] it answers a Confirmable Q-Block request with 4.02 and a Non-confirmable one with a Reset,
 * like a server without Q-Block (RFC 9177 §4.1).
 */
internal class QBlockTestServer(
    private val transport: InMemoryTransport,
    scope: CoroutineScope,
    private val clock: () -> Duration,
    parameters: TransmissionParameters = TransmissionParameters(),
    private val nonTimeoutRandom: Duration = 2.5.seconds,
) {
    private val timing = QBlockTiming(parameters)
    private val lifetime = parameters.nonPartialTimeout

    val address: Endpoint.Udp get() = transport.localEndpoint

    fun uri(path: String): String = "coap://${address.host}:${address.port}$path"

    /** Bodies by path. */
    val resources = HashMap<String, ByteArray>()

    /** Whether Q-Block is understood. */
    var supportsQBlock: Boolean = true

    /** Whether every message is sent twice, as a network that duplicates datagrams would deliver it. */
    var duplicates: Boolean = false

    /** The largest block the server sends. */
    var maxBlockSize: Int = 1024

    /** What a FETCH is answered with, from its body. */
    var fetch: (ByteArray) -> ByteArray = { it }

    /** Answers a request by hand when it returns true; it may [send] whatever it wants. */
    var script: (suspend QBlockTestServer.(Endpoint, CoapMessage) -> Boolean)? = null

    /** Every assembled request the server handled. */
    val handled = mutableListOf<CoapMessage>()

    /** Why partial uploads were discarded. */
    val discarded = mutableListOf<String>()

    /** What went wrong in the server itself; a test that sees any has a broken script. */
    val errors = mutableListOf<Exception>()

    /** Partial uploads in progress. */
    val partialBodies: Int get() = uploads.size

    /** How many Q-Block2 bodies were served, each with an ETag of its own. */
    var servedBodies: Int = 0
        private set

    private class Ref(val peer: Endpoint, val token: ByteArray, val messageId: Int, val type: CoapMessageType) {
        var answered = false
        var reply: CoapMessage? = null

        override fun toString(): String = "Ref(${token.toHex()})"
    }

    private class Upload(val receiver: QBlock1Receiver<Ref>, val path: String)

    private class Served(
        val key: String,
        val sender: QBlock2Sender<Ref>,
        val body: ByteArray,
        val etag: Int,
        val contentFormat: Int?,
        var lastUse: Duration,
    )

    private val uploads = LinkedHashMap<String, Upload>()
    private val completed = HashMap<String, Pair<CoapMessage, Duration>>()
    private val served = HashMap<String, Served>()
    private val replies = HashMap<String, Ref>()
    private val outstanding = HashMap<Int, Pair<String, Long>>()
    private val requestsOf408 = HashMap<Int, String>()
    private var nextMessageId = 0x5000
    private var nextETag = 0x20
    private val incoming = Channel<DatagramEvent>(Channel.UNLIMITED)

    init {
        scope.launch { while (true) incoming.send(transport.receive()) }
        scope.launch { run() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        while (true) {
            val wait = nextDeadline()?.let { it - clock() }
            val event = if (wait == null) {
                incoming.receive()
            } else if (!wait.isPositive()) {
                incoming.tryReceive().getOrNull()
            } else {
                select {
                    incoming.onReceive { it }
                    onTimeout(wait) { null }
                }
            }
            try {
                if (event == null) onTimer() else if (event is DatagramEvent.Received) onDatagram(event.from, event.datagram)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += e
            }
        }
    }

    private fun nextDeadline(): Duration? =
        (uploads.values.mapNotNull { it.receiver.nextDeadline() } + served.values.mapNotNull { it.sender.nextDeadline() }).minOrNull()

    private suspend fun onTimer() {
        val now = clock()
        for ((key, upload) in uploads.entries.toList()) {
            if (upload.receiver.nextDeadline()?.let { it <= now } == true) uploadActions(key, upload.receiver.onTimer(now))
        }
        for (body in served.values.toList()) {
            if (body.sender.nextDeadline()?.let { it <= now } == true) servedActions(body, body.sender.onTimer(now))
        }
        completed.entries.removeAll { it.value.second + lifetime <= now }
        served.entries.removeAll { it.value.lastUse + lifetime <= now && !it.value.sender.isSending }
    }

    private suspend fun onDatagram(from: Endpoint, datagram: ByteArray) {
        val message = runCatching { UdpMessageCodec.decode(datagram) }.getOrNull() ?: return
        when {
            message.type == CoapMessageType.ACK && message.isEmpty -> onAcknowledged(message.messageId)
            message.type == CoapMessageType.RST -> onReset(message.messageId)
            message.isRequest -> onRequest(from, message)
            else -> Unit
        }
    }

    private suspend fun onAcknowledged(messageId: Int) {
        val (key, num) = outstanding.remove(messageId) ?: return
        val body = served[key] ?: return
        servedActions(body, body.sender.onAcknowledged(clock(), num))
    }

    private suspend fun onReset(messageId: Int) {
        requestsOf408.remove(messageId)?.let { key ->
            val upload = uploads[key] ?: return
            uploadActions(key, upload.receiver.onReset(clock()))
            return
        }
        outstanding.remove(messageId)?.let { (key, _) ->
            val body = served[key] ?: return
            servedActions(body, body.sender.onReset(clock()))
        }
    }

    private suspend fun onRequest(from: Endpoint, request: CoapMessage) {
        val duplicateKey = "$from/${request.messageId}"
        if (request.type == CoapMessageType.CON) {
            replies[duplicateKey]?.let { earlier ->
                earlier.reply?.let { send(from, it) }
                return
            }
        }
        val ref = Ref(from, request.token, request.messageId, request.type)
        if (request.type == CoapMessageType.CON) replies[duplicateKey] = ref
        if (script?.invoke(this, from, request) == true) return
        val options = request.options
        val hasQBlock = CoapOptions.Q_BLOCK1.number in options || CoapOptions.Q_BLOCK2.number in options
        if (hasQBlock && !supportsQBlock) {
            if (request.type == CoapMessageType.CON) {
                respond(ref, CoapCode.BAD_OPTION) { payload("Unrecognized critical option") }
            } else {
                send(from, CoapMessage.empty(CoapMessageType.RST, request.messageId))
            }
            return
        }
        QBlockCheck.request(request, allowsBert = false, maxBodySize = 1 shl 20)?.let { violation ->
            respond(ref, violation.code) { payload(violation.diagnostic) }
            return
        }
        val path = options.path
        val qBlock1 = options.qBlock1
        val qBlock2 = options.qBlock2List
        val key = "$from$path"
        when {
            qBlock1 != null -> onBlock(ref, request, "$key/${options.requestTags.first().toHex()}", path)
            path == "/.well-known/core" && qBlock2.singleOrNull()?.let { it.number == 0L && !it.more } == true ->
                respond(ref, CoapCode.CONTENT) {
                    options {
                        contentFormat(ContentFormat.LINK_FORMAT)
                        qBlock2(BlockOption(0, true, 0))
                    }
                    payload("</>;title=\"Gener")
                }
            qBlock2.isNotEmpty() && !(qBlock2.size == 1 && qBlock2[0].number == 0L && qBlock2[0].more) && key in served -> {
                val body = served.getValue(key)
                body.lastUse = clock()
                servedActions(body, body.sender.onRequest(clock(), request, ref))
            }
            else -> handle(ref, request, path)
        }
    }

    private suspend fun onBlock(ref: Ref, request: CoapMessage, key: String, path: String) {
        val now = clock()
        completed[key]?.let { (final, _) ->
            if (request.options.qBlock1?.more == false) respond(ref, final.code) { options { addAll(final.options) } } else noResponse(ref)
            return
        }
        val upload = uploads.getOrPut(key) {
            Upload(QBlock1Receiver(request, BodyMode.of(request.type, isReliable = false), timing, now), path)
        }
        uploadActions(key, upload.receiver.onBlock(now, request, ref))
    }

    private suspend fun uploadActions(key: String, actions: List<QBlockAction<Ref>>) {
        for (action in actions) {
            when (action) {
                is QBlockAction.NoResponse -> noResponse(action.ref)
                is QBlockAction.Send231 -> respond(action.ref, CoapCode.CONTINUE) {
                    options { qBlock1(BlockOption(action.num, true, uploads.getValue(key).receiver.sizeExponent)) }
                }
                is QBlockAction.Send408 -> {
                    val message = CoapMessage.build {
                        type = CoapMessageType.NON
                        code = CoapCode.REQUEST_ENTITY_INCOMPLETE
                        messageId = nextMessageId++
                        token = action.ref.token
                        payload = MissingBlocks.encode(action.missing)
                        options { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
                    }
                    requestsOf408[message.messageId] = key
                    send(action.ref.peer, message)
                }
                is QBlockAction.Reject -> respond(action.ref, action.code) { payload(action.diagnostic) }
                is QBlockAction.Deliver -> {
                    val upload = checkNotNull(uploads.remove(key))
                    val ref = checkNotNull(action.ref)
                    val final = handle(ref, action.message, upload.path, uploaded = true)
                    if (final != null) completed[key] = final to clock()
                }
                is QBlockAction.Discard -> {
                    uploads.remove(key)
                    discarded += action.reason
                }
                else -> Unit
            }
        }
    }

    /** The application: answers [request] on [ref]; returns the final response unless a body is served. */
    private suspend fun handle(ref: Ref, request: CoapMessage, path: String, uploaded: Boolean = false): CoapMessage? {
        handled += request
        val wantsQBlock = uploaded || CoapOptions.Q_BLOCK2.number in request.options
        val body = when (request.code) {
            CoapCode.GET -> resources[path] ?: return respond(ref, CoapCode.NOT_FOUND) {}
            CoapCode.FETCH -> fetch(request.payload)
            CoapCode.PUT, CoapCode.POST -> {
                resources[path] = request.payload
                return respond(ref, CoapCode.CHANGED) {}
            }
            CoapCode.DELETE -> {
                resources.remove(path)
                return respond(ref, CoapCode.DELETED) {}
            }
            else -> return respond(ref, CoapCode.METHOD_NOT_ALLOWED) {}
        }
        if (!wantsQBlock) return respond(ref, CoapCode.CONTENT) { payload = body }
        val requested = request.options.qBlock2?.size ?: maxBlockSize
        val sender = QBlock2Sender<Ref>(body.size, minOf(requested, maxBlockSize), BodyMode.of(ref.type, false), timing, nonTimeoutRandom)
        val served = Served("${ref.peer}$path", sender, body, nextETag++, request.options.contentFormat, clock())
        this.served[served.key] = served
        servedBodies++
        servedActions(served, sender.start(clock(), ref))
        return null
    }

    private suspend fun servedActions(body: Served, actions: List<QBlockAction<Ref>>) {
        for (action in actions) {
            when (action) {
                is QBlockAction.SendBlock -> {
                    val sender = body.sender
                    val offset = (action.num * sender.blockSize).toInt()
                    val block = respond(action.ref, CoapCode.CONTENT, confirmable = action.ref.type == CoapMessageType.CON) {
                        payload = body.body.copyOfRange(offset, minOf(body.body.size, offset + sender.blockSize))
                        options {
                            etag(byteArrayOf(body.etag.toByte()))
                            size2(body.body.size.toLong())
                            body.contentFormat?.let { contentFormat(it) }
                            qBlock2(BlockOption(action.num, action.more, sender.sizeExponent))
                        }
                    }
                    if (block.type == CoapMessageType.CON) {
                        outstanding[block.messageId] = body.key to action.num
                    }
                }
                is QBlockAction.NoResponse -> noResponse(action.ref)
                is QBlockAction.Reject -> respond(action.ref, action.code) { payload(action.diagnostic) }
                else -> Unit
            }
        }
    }

    /**
     * Answers [ref] with [code]: piggybacked on the ACK of a Confirmable request not answered yet, otherwise
     * separately, Confirmable if [confirmable], with a Message ID of its own.
     */
    private suspend fun respond(
        ref: Ref,
        code: CoapCode,
        confirmable: Boolean = false,
        block: CoapMessage.Builder.() -> Unit,
    ): CoapMessage {
        val piggybacked = ref.type == CoapMessageType.CON && !ref.answered
        val message = CoapMessage.build {
            type = when {
                piggybacked -> CoapMessageType.ACK
                confirmable -> CoapMessageType.CON
                else -> CoapMessageType.NON
            }
            this.code = code
            messageId = if (piggybacked) ref.messageId else nextMessageId++
            token = ref.token
            block()
        }
        if (piggybacked) {
            ref.answered = true
            ref.reply = message
        }
        send(ref.peer, message)
        return message
    }

    private suspend fun noResponse(ref: Ref) {
        if (ref.type != CoapMessageType.CON || ref.answered) return
        ref.answered = true
        val ack = CoapMessage.empty(CoapMessageType.ACK, ref.messageId)
        ref.reply = ack
        send(ref.peer, ack)
    }

    /** Forgets the bodies served for [path], so the next request for blocks runs the handler again, as libcoap does. */
    fun forgetServed(path: String) {
        served.entries.removeAll { it.key.endsWith(path) }
    }

    suspend fun send(to: Endpoint, message: CoapMessage) {
        repeat(if (duplicates) 2 else 1) { transport.send(to, UdpMessageCodec.encode(message)) }
    }

    override fun toString(): String = "QBlockTestServer($address)"
}

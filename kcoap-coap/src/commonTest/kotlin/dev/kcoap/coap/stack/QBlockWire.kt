package dev.kcoap.coap.stack

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.ContentFormat
import dev.kcoap.common.toHex
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A client and a server that run the Q-Block state machines over two real [UdpMessaging] layers (or, for a
 * reliable transport, a direct ordered pipe) on virtual time, as small test drivers would. Every message on
 * the wire is recorded in [trace], with its time, and can be dropped by [drop], so the RFC 9177 example
 * figures can be scripted message by message.
 *
 * Delivery takes no time and is first in, first out: every message a step sends is on the wire before the
 * first of them is processed, as a burst of blocks would be. At the same instant, timers run in the order
 * messaging layers, server machines, client machines.
 */
internal class QBlockWire(
    val parameters: TransmissionParameters = TransmissionParameters(),
    val reliable: Boolean = false,
) {
    val timing = QBlockTiming(parameters)

    var now: Duration = Duration.ZERO
        private set

    /** Every message sent, in order. */
    val trace = mutableListOf<Line>()

    /** Drops the message of a line when it returns true; [Line.copy] counts earlier copies of the same block. */
    var drop: (Line) -> Boolean = { false }

    val client = Client()
    val server = Server()

    private val clientAddress = Endpoint.Udp("192.0.2.2", 40000)
    private val serverAddress = Endpoint.Udp("192.0.2.1", 5683)
    private val clientMessaging = UdpMessaging(parameters, 1152, null, FixedRandom)
    private val serverMessaging = UdpMessaging(parameters, 1152, null, FixedRandom)

    /** Messages on their way, delivered in order by [pump]. */
    private val inFlight = ArrayDeque<() -> Unit>()

    /** Delivers every message on its way, and what their receivers send, at the current instant. */
    private fun pump() {
        var deliveries = 0
        while (inFlight.isNotEmpty()) {
            check(++deliveries < 100_000) { "The peers do not stop talking" }
            inFlight.removeFirst()()
        }
    }

    /** One message on the wire. [copy] is 1 for the first message of its kind and block, 2 for the next, and so on. */
    class Line(val at: Duration, val fromClient: Boolean, val message: CoapMessage, val copy: Int, private val reliable: Boolean) {
        var dropped: Boolean = false
            internal set

        /** The block number the message carries, if any. */
        val num: Long? get() = (message.options.qBlock1 ?: message.options.qBlock2)?.number

        /** `2.5s C>S NON PUT T3 QB1:3/0/1024`, prefixed with `X ` when dropped. */
        override fun toString(): String = buildString {
            if (dropped) append("X ")
            append(seconds(at)).append(' ').append(if (fromClient) "C>S" else "S>C")
            if (!reliable) append(' ').append(message.type)
            append(' ').append(if (message.isEmpty) "0.00" else codeName(message.code))
            if (message.token.isNotEmpty()) append(" T").append(tokenIndex(message.token))
            message.options.observe?.let { append(" Obs:").append(it) }
            message.options.qBlock1?.let { append(" QB1:").append(it) }
            message.options.qBlock2List.forEach { append(" QB2:").append(it) }
            message.options.etags.forEach { append(" ETag:").append(it.toHex()) }
            if (message.options.contentFormat == ContentFormat.MISSING_BLOCKS_CBOR_SEQ) {
                append(' ').append(MissingBlocks.decode(message.payload))
            }
        }

        private fun codeName(code: CoapCode): String = if (code.isRequest) {
            listOf("GET", "POST", "PUT", "DELETE", "FETCH", "PATCH", "IPATCH")[code.detail - 1]
        } else {
            code.toString().take(4)
        }
    }

    // ---------------------------------------------------------------- time

    /** Runs every timer up to [limit] and leaves the clock there. */
    fun runUntil(limit: Duration) {
        var rounds = 0
        while (true) {
            check(++rounds < 100_000) { "The timers do not settle" }
            val next = deadlines().minOrNull() ?: break
            if (next > limit) break
            now = maxOf(now, next)
            clientMessaging.onTimer(now)
            clientEffects()
            serverMessaging.onTimer(now)
            serverEffects()
            pump()
            server.onTimer()
            pump()
            client.onTimer()
            pump()
        }
        now = maxOf(now, limit)
    }

    /** Moves the clock to [at] without running timers in between (none may be due). */
    fun advanceTo(at: Duration) {
        check(deadlines().none { it < at }) { "A timer is due before $at: ${deadlines()}" }
        now = at
    }

    private fun deadlines(): List<Duration> = listOfNotNull(
        clientMessaging.nextDeadline(), serverMessaging.nextDeadline(),
        client.sender?.nextDeadline(), client.receiver?.nextDeadline(),
        server.served?.nextDeadline(),
    ) + server.uploads.values.mapNotNull { it.nextDeadline() }

    // ---------------------------------------------------------------- the wire

    private fun record(fromClient: Boolean, message: CoapMessage): Line {
        val num = (message.options.qBlock1 ?: message.options.qBlock2)?.number
        val copy = 1 + trace.count {
            it.fromClient == fromClient && it.message.code == message.code && it.num == num && num != null
        }
        val line = Line(now, fromClient, message, copy, reliable)
        trace += line
        line.dropped = drop(line)
        return line
    }

    private fun clientEffects() {
        for (effect in clientMessaging.takeEffects()) {
            when (effect) {
                is MessagingEffect.Transmit -> {
                    val line = record(true, dev.kcoap.coap.codec.UdpMessageCodec.decode(effect.bytes))
                    if (!line.dropped) {
                        inFlight.addLast {
                            serverMessaging.onDatagram(now, clientAddress, effect.bytes)
                            serverEffects()
                        }
                    }
                }
                is MessagingEffect.DeliverResponse -> {
                    clientMessaging.acknowledge(effect.from, effect.message.messageId)
                    clientEffects()
                    client.onResponse(effect.message)
                }
                is MessagingEffect.Completed -> client.onOutcome(effect.transmission.message, effect.outcome)
                else -> Unit
            }
        }
    }

    private fun serverEffects() {
        for (effect in serverMessaging.takeEffects()) {
            when (effect) {
                is MessagingEffect.Transmit -> {
                    val line = record(false, dev.kcoap.coap.codec.UdpMessageCodec.decode(effect.bytes))
                    if (!line.dropped) {
                        inFlight.addLast {
                            clientMessaging.onDatagram(now, serverAddress, effect.bytes)
                            clientEffects()
                        }
                    }
                }
                is MessagingEffect.DeliverRequest -> server.onRequest(effect.message)
                is MessagingEffect.Completed -> server.onOutcome(effect.transmission.message, effect.outcome)
                else -> Unit
            }
        }
    }

    // ---------------------------------------------------------------- client

    inner class Client {
        var method: CoapCode = CoapCode.PUT
        var observe: Long? = null
        var blockSize: Int = 1024
        var requestTag: ByteArray = byteArrayOf(9)
        var nonTimeoutRandom: Duration = 2.5.seconds
        var missingBlockMBit: Boolean = false

        /** Whether a Continue or request for missing blocks after an upload repeats its Request-Tag (§4.7). */
        var followUpRequestTag: Boolean = true

        var sender: QBlock1Sender? = null
            private set
        var receiver: QBlock2Receiver? = null
            private set

        /** Every action the client machines asked for. */
        val actions = mutableListOf<QBlockAction<*>>()

        /** Final responses and assembled bodies, in order. */
        val delivered = mutableListOf<CoapMessage>()

        /** Tokens released by the upload. */
        val released = mutableSetOf<Int>()

        private var confirmable = false
        private var nextToken = 0
        private var body = ByteArray(0)
        private val tokenOfSequence = HashMap<Int, Int>()
        private val sequenceOfToken = HashMap<Int, Int>()

        /** Uploads [body] with Q-Block1, Non-confirmable unless [confirmable]. */
        fun upload(body: ByteArray, confirmable: Boolean = false, resendsLastBlock: Boolean = true) {
            this.body = body
            this.confirmable = confirmable
            val mode = mode(confirmable)
            val sender = QBlock1Sender(body.size, blockSize, mode, timing, nonTimeoutRandom, resendsLastBlock)
            this.sender = sender
            execute(sender.start(now))
            pump()
        }

        /** Asks for a body with Q-Block2 0/M=1 (§4.4), Non-confirmable unless [confirmable]. */
        fun download(confirmable: Boolean = false) {
            this.confirmable = confirmable
            send(request(nextToken++) { qBlock2(BlockOption(0, true, BlockOption.sizeExponentFor(blockSize))) })
            pump()
        }

        internal fun onResponse(response: CoapMessage) {
            val token = tokenIndex(response.token)
            val sequence = sequenceOfToken[token]
            val sender = sender
            if (sender != null && sequence != null && !sender.isFinished) {
                execute(sender.onResponse(now, sequence, response))
            } else {
                onFinal(response)
            }
        }

        internal fun onOutcome(request: CoapMessage, outcome: TransmissionOutcome) {
            val sequence = sequenceOfToken[tokenIndex(request.token)]
            val sender = sender
            when (outcome) {
                is TransmissionOutcome.Acknowledged -> {
                    val piggybacked = outcome.piggybackedResponse
                    when {
                        piggybacked != null -> onResponse(piggybacked)
                        sender != null && sequence != null -> execute(sender.onAcknowledged(now, sequence))
                    }
                }
                TransmissionOutcome.TimedOut -> if (sender != null && sequence != null) execute(sender.onTimeout(now, sequence))
                TransmissionOutcome.Reset -> if (sender != null && sequence != null) execute(sender.onReset(now, sequence))
                else -> Unit
            }
        }

        internal fun onTimer() {
            sender?.let { if (it.nextDeadline()?.let { at -> at <= now } == true) execute(it.onTimer(now)) }
            receiver?.let { if (it.nextDeadline()?.let { at -> at <= now } == true) execute(it.onTimer(now)) }
        }

        /** A final response or a block of a Q-Block2 body. */
        private fun onFinal(response: CoapMessage) {
            if (response.options.qBlock2 == null) {
                delivered += response
                return
            }
            val receiver = receiver ?: QBlock2Receiver(
                mode(confirmable), timing, maxBodySize = 1 shl 20,
                restartable = method == CoapCode.GET || method == CoapCode.FETCH, missingBlockMBit = missingBlockMBit,
            ).also { receiver = it }
            execute(receiver.onBlock(now, response))
        }

        private fun execute(list: List<QBlockAction<*>>) {
            for (action in list) {
                actions += action
                when (action) {
                    is QBlockAction.SendBlock<*> -> sendBlock(action.num, action.more, action.ref as Int)
                    is QBlockAction.SendContinue -> send(followUp { qBlock2(BlockOption(action.num, true, exponent())) })
                    is QBlockAction.RequestMissing -> send(followUp { action.blocks.forEach { addQBlock2(it) } })
                    is QBlockAction.Deliver<*> -> onFinal(action.message)
                    is QBlockAction.Release<*> -> action.refs.forEach { released += tokenOfSequence.getValue(it as Int) }
                    else -> Unit
                }
            }
        }

        private fun exponent(): Int = BlockOption.sizeExponentFor(blockSize)

        private fun mode(confirmable: Boolean): BodyMode = when {
            reliable -> BodyMode.RELIABLE
            confirmable -> BodyMode.CON
            else -> BodyMode.NON
        }

        private fun sendBlock(num: Long, more: Boolean, sequence: Int) {
            val token = nextToken++
            tokenOfSequence[sequence] = token
            sequenceOfToken[token] = sequence
            val offset = (num * blockSize).toInt()
            send(
                request(token) {
                    requestTag(requestTag)
                    size1(body.size.toLong())
                    qBlock1(BlockOption(num, more, exponent()))
                }.toBuilder().apply { payload = body.copyOfRange(offset, minOf(body.size, offset + blockSize)) }.build(),
            )
        }

        /** A Continue or a request for missing blocks: the request without Observe, payload or Q-Block1 (§4.4). */
        private fun followUp(block: dev.kcoap.coap.OptionSet.Builder.() -> Unit): CoapMessage = request(nextToken++, observe = null) {
            if (sender != null && followUpRequestTag) requestTag(requestTag)
            block()
        }

        private fun request(token: Int, observe: Long? = this.observe, block: dev.kcoap.coap.OptionSet.Builder.() -> Unit) =
            CoapMessage.request(method) {
                this.token = tokenBytes(token)
                options {
                    uriPath("path")
                    observe?.let { observe(it) }
                    block()
                }
            }

        private fun send(request: CoapMessage) {
            if (reliable) {
                if (!record(true, request).dropped) inFlight.addLast { server.onRequest(request) }
                return
            }
            val type = if (confirmable) CoapMessageType.CON else CoapMessageType.NON
            clientMessaging.send(now, serverAddress, request.withHeader(type, clientMessaging.allocateMessageId(serverAddress, now)), null)
            clientEffects()
        }
    }

    // ---------------------------------------------------------------- server

    /** The server's handle of a request: its token, Message ID and type. */
    class Ref(val token: Int, val messageId: Int, val type: CoapMessageType) {
        override fun toString(): String = "T$token"
    }

    inner class Server {
        /** The body served for a request that asks for one; a 2.04 without body otherwise. */
        var responseBody: (CoapMessage) -> ByteArray? = { null }
        var blockSize: Int = 1024
        var nonTimeoutRandom: Duration = 2.5.seconds
        var etag: Int = 0x10
        var observe: Long = 1000

        /** Partial uploads by Request-Tag. */
        val uploads = LinkedHashMap<String, QBlock1Receiver<Ref>>()
        var served: QBlock2Sender<Ref>? = null
            private set

        /** Every action the server machines asked for. */
        val actions = mutableListOf<QBlockAction<*>>()

        /** Assembled request bodies. */
        val received = mutableListOf<CoapMessage>()
        private var servedBody = ByteArray(0)
        private var original: Ref? = null
        private var observing = false

        internal fun onRequest(request: CoapMessage) {
            val ref = Ref(tokenIndex(request.token), request.messageId, if (reliable) CoapMessageType.CON else request.type)
            val violation = QBlockCheck.request(request, allowsBert = false, maxBodySize = 1 shl 20)
            if (violation != null) {
                respond(ref, CoapMessage.response(request, violation.code) { payload(violation.diagnostic) })
                return
            }
            val options = request.options
            if (options.qBlock1 != null) {
                val key = options.requestTags.first().toHex()
                val receiver = uploads.getOrPut(key) { QBlock1Receiver(request, BodyMode.of(request.type, reliable), timing, now) }
                execute(receiver.onBlock(now, request, ref), key)
                return
            }
            val served = served
            val qBlock2 = options.qBlock2List
            val wholeBody = qBlock2.size == 1 && qBlock2[0].number == 0L && qBlock2[0].more
            if (served != null && qBlock2.isNotEmpty() && !wholeBody) {
                execute(served.onRequest(now, request, ref), null)
                return
            }
            serve(request, ref, responseBody(request) ?: ByteArray(0))
        }

        /** The resource changed: the next notification body goes out on the registration (RFC 7641, §4.5). */
        fun notify(body: ByteArray) {
            val ref = checkNotNull(original)
            observe++
            etag++
            start(ref, body, BodyMode.of(ref.type, reliable), piggyback = false)
            pump()
        }

        internal fun onOutcome(response: CoapMessage, outcome: TransmissionOutcome) {
            val served = served ?: return
            val num = response.options.qBlock2?.number ?: return
            when (outcome) {
                is TransmissionOutcome.Acknowledged -> execute(served.onAcknowledged(now, num), null)
                TransmissionOutcome.TimedOut -> execute(served.onTimeout(now, num), null)
                TransmissionOutcome.Reset -> execute(served.onReset(now), null)
                else -> Unit
            }
        }

        internal fun onTimer() {
            for ((key, receiver) in uploads.entries.toList()) {
                if (receiver.nextDeadline()?.let { it <= now } == true) execute(receiver.onTimer(now), key)
            }
            served?.let { if (it.nextDeadline()?.let { at -> at <= now } == true) execute(it.onTimer(now), null) }
        }

        private fun serve(request: CoapMessage, ref: Ref, body: ByteArray) {
            // Every body gets an ETag of its own (§4.4), also when the handler runs again for 0/M=1.
            if (served != null) etag++
            observing = request.options.observe == 0L
            original = ref
            start(ref, body, BodyMode.of(ref.type, reliable), piggyback = ref.type == CoapMessageType.CON && !reliable)
        }

        private fun start(ref: Ref, body: ByteArray, mode: BodyMode, piggyback: Boolean) {
            servedBody = body
            val sender = QBlock2Sender<Ref>(body.size, blockSize, mode, timing, nonTimeoutRandom, piggyback)
            served = sender
            execute(sender.start(now, ref), null)
        }

        private fun execute(list: List<QBlockAction<Ref>>, upload: String?) {
            for (action in list) {
                actions += action
                when (action) {
                    is QBlockAction.SendBlock -> sendBlock(action.num, action.more, action.ref)
                    is QBlockAction.Send231 -> respond(action.ref, response(action.ref, CoapCode.CONTINUE) {
                        options { qBlock1(BlockOption(action.num, true, BlockOption.sizeExponentFor(blockSize))) }
                    })
                    is QBlockAction.Send408 -> respond(action.ref, response(action.ref, CoapCode.REQUEST_ENTITY_INCOMPLETE) {
                        options { contentFormat(ContentFormat.MISSING_BLOCKS_CBOR_SEQ) }
                        payload = MissingBlocks.encode(action.missing)
                    })
                    is QBlockAction.NoResponse -> if (action.ref.type == CoapMessageType.CON && !reliable) {
                        serverMessaging.acknowledge(clientAddress, action.ref.messageId)
                        serverEffects()
                    }
                    is QBlockAction.Reject -> respond(action.ref, response(action.ref, action.code) { payload(action.diagnostic) })
                    is QBlockAction.Deliver -> {
                        uploads.remove(upload)
                        received += action.message
                        val ref = checkNotNull(action.ref)
                        val body = responseBody(action.message)
                        if (body == null) respond(ref, response(ref, CoapCode.CHANGED) {}) else serve(action.message, ref, body)
                    }
                    is QBlockAction.Discard -> uploads.remove(upload)
                    else -> Unit
                }
            }
        }

        private fun sendBlock(num: Long, more: Boolean, ref: Ref) {
            val offset = (num * blockSize).toInt()
            respond(ref, response(ref, CoapCode.CONTENT) {
                options {
                    if (observing && ref === original) observe(observe)
                    etag(byteArrayOf(etag.toByte()))
                    size2(servedBody.size.toLong())
                    qBlock2(BlockOption(num, more, BlockOption.sizeExponentFor(blockSize)))
                }
                payload = servedBody.copyOfRange(offset, minOf(servedBody.size, offset + blockSize))
            })
        }

        private fun response(ref: Ref, code: CoapCode, block: CoapMessage.Builder.() -> Unit): CoapMessage =
            CoapMessage.build {
                this.code = code
                token = tokenBytes(ref.token)
                block()
            }

        /** Piggybacked while the Confirmable request [ref] is unanswered, otherwise separate in its type (RFC 7252 §5.2). */
        private fun respond(ref: Ref, response: CoapMessage) {
            if (reliable) {
                if (!record(false, response).dropped) inFlight.addLast { client.onResponse(response) }
                return
            }
            serverMessaging.respond(now, clientAddress, ref.messageId, ref.type, response)
            serverEffects()
        }
    }

    companion object {
        /** A random source whose doubles are 0, so retransmission timeouts are exactly ACK_TIMEOUT, and MIDs start at 0. */
        private val FixedRandom = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0

            override fun nextDouble(): Double = 0.0
        }

        fun tokenBytes(index: Int): ByteArray = byteArrayOf((index ushr 8).toByte(), index.toByte())

        fun tokenIndex(token: ByteArray): Int = if (token.size == 2) (token[0].toInt() and 0xFF shl 8) or (token[1].toInt() and 0xFF) else -1

        /** `0s`, `2.5s`, `124s`. */
        fun seconds(at: Duration): String {
            val millis = at.inWholeMilliseconds
            val fraction = (millis % 1000).toString().padStart(3, '0').trimEnd('0')
            return if (fraction.isEmpty()) "${millis / 1000}s" else "${millis / 1000}.${fraction}s"
        }
    }
}

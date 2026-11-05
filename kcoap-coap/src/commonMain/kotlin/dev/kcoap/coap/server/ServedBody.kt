package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.stack.BodyMode
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.QBlock2Sender
import dev.kcoap.transport.Endpoint
import kotlin.concurrent.Volatile
import kotlin.time.Duration

/**
 * A response body the server sends with Q-Block2 (RFC 9177 §4.4) and keeps for requests of its missing blocks
 * and Continues: the [response] cut into blocks of [QBlock2Sender.blockSize] bytes, the [sender] that decides
 * which block goes on which target and when, the operation it answers ([key]) and the timer that sends its
 * later sets. Guarded by [QBlockServer]'s lock, except [isDone].
 *
 * Every block carries the same [etag], unique for this transmitted body (§4.4), and Size2 with the whole
 * body's size (§4.6); the response's own ETag, Observe and block-wise options are left out. A body of an
 * [observation], the registration response or a notification, puts its [observe] value on every block to a
 * target that [QBlockTarget.carriesObserve] (§4.5).
 */
internal class ServedBody(
    val key: OperationKey,
    val response: CoapResponse,
    etag: ByteArray,
    val sender: QBlock2Sender<QBlockTarget>,
    /** When the body started, for how long a Non-confirmable notification body stays outstanding. */
    val startedAt: Duration = Duration.ZERO,
    /** The observation whose registration response or notification this body is (RFC 7641 §4.2). */
    val observation: ServerObservation? = null,
    /** The target of a notification body's own blocks; null for a body that answers a request. */
    val notification: NotificationBody? = null,
) : TimedBody() {
    private val etagBytes = etag.copyOf()
    private val body = response.payload
    private val options = response.options.toBuilder().apply { STRIPPED.forEach { remove(it) } }.build()

    /** The Observe value of a body of an [observation] (RFC 7641 §4.4), the same on all its blocks (§4.5). */
    val observe: Long? = response.options.observe

    val peer: Endpoint get() = key.peer

    val etag: ByteArray get() = etagBytes.copyOf()

    /**
     * Whether the body is no longer the outstanding notification of its [observation]: every block went out
     * (and was acknowledged, if Confirmable), it stopped, or it was dropped. Set once, under [QBlockServer]'s
     * lock; read by the observation under its own.
     */
    @Volatile
    var isDone: Boolean = false
        private set

    /** Runs once when the body becomes [isDone]; it must not suspend or take a lock. */
    var onDone: (() -> Unit)? = null

    /** Marks the body [isDone], once. */
    fun markDone() {
        if (isDone) return
        isDone = true
        onDone?.invoke()
    }

    /**
     * Whether the body is [isDone] and, if Non-confirmable, at least [interval] old, as a single
     * Non-confirmable notification keeps its NSTART slot for [CoapServerConfig.nonConfirmableNotificationInterval].
     */
    fun isSettled(now: Duration, interval: Duration): Boolean =
        isDone && (sender.mode != BodyMode.NON || now >= startedAt + interval)

    /** The Observe value for a block to [target]: the body's, when the target carries Observe (§4.5). */
    fun observeFor(target: QBlockTarget): Long? = observe?.takeIf { target.carriesObserve }

    /**
     * Block [num] with M = [more] as a response with [token], carrying [observe] if not null; the type and
     * Message ID are left to messaging.
     */
    fun block(num: Long, more: Boolean, token: ByteArray, observe: Long? = null): CoapMessage {
        val size = sender.blockSize
        val start = minOf(body.size.toLong(), num * size).toInt()
        val end = minOf(body.size, start + size)
        val blockOptions = options.toBuilder().apply {
            etag(etagBytes)
            size2(body.size.toLong())
            qBlock2(BlockOption(num, more, sender.sizeExponent))
            observe?.let { observe(it) }
        }.build()
        return CoapResponse(response.code, blockOptions, body.copyOfRange(start, end)).toMessage(token)
    }

    /** A dropped body is done too: nothing of it goes out any more. */
    override fun drop() {
        super.drop()
        markDone()
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

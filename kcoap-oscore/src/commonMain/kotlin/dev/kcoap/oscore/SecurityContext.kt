package dev.kcoap.oscore

import dev.kcoap.cbor.CborWriter
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.Hkdf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A derived OSCORE security context (RFC 8613 §3): the Common Context (keys' common inputs and Common
 * IV), the Sender Context (Sender Key and Sequence Number) and the Recipient Context (Recipient Key and
 * Replay Window).
 *
 * Thread safe: sequence numbers are handed out and the replay window updated under a lock (§7.2, §7.4).
 * Keys never leave the object; `toString()` shows only identifiers.
 */
public class SecurityContext private constructor(
    public val parameters: SecurityContextParameters,
    internal val senderKey: ByteArray,
    internal val recipientKey: ByteArray,
    internal val commonIv: ByteArray,
    internal val senderAead: Aead,
    internal val recipientAead: Aead,
    private val sequenceStore: SequenceNumberStore,
    private var nextSequenceNumber: Long,
    /** Persistence block size K of Appendix B.1.1. */
    private val persistInterval: Long,
) {
    private val senderLock = Mutex()
    private val recipientLock = Mutex()
    private var persistedLimit: Long = nextSequenceNumber
    private val replayWindow = ReplayWindow(parameters.replayWindowSize)

    public val senderId: ByteArray get() = parameters.senderId
    public val recipientId: ByteArray get() = parameters.recipientId
    public val idContext: ByteArray? get() = parameters.idContext

    /** The next Sender Sequence Number this context will use. */
    public suspend fun peekSequenceNumber(): Long = senderLock.withLock { nextSequenceNumber }

    /** Takes the next Sender Sequence Number, persisting a new block first when needed (Appendix B.1.1). */
    internal suspend fun takeSequenceNumber(): Long = senderLock.withLock {
        val number = nextSequenceNumber
        if (number > MAX_SEQUENCE_NUMBER) throw OscoreException(OscoreError.SEQUENCE_EXHAUSTED)
        if (number >= persistedLimit) {
            persistedLimit = minOf(number + persistInterval, MAX_SEQUENCE_NUMBER + 1)
            sequenceStore.store(parameters.name, persistedLimit)
        }
        nextSequenceNumber = number + 1
        number
    }

    /** Whether [sequence] from the peer was already accepted (or is too old for the window). */
    internal suspend fun isReplay(sequence: Long): Boolean = recipientLock.withLock { replayWindow.isReplay(sequence) }

    /** Records an authenticated [sequence]; false if a concurrent message took it first. */
    internal suspend fun acceptSequence(sequence: Long): Boolean = recipientLock.withLock { replayWindow.accept(sequence) }

    /**
     * The AEAD nonce (RFC 8613 §5.2): the length of [idPiv], the padded ID of the endpoint that chose the
     * Partial IV, and the padded Partial IV, XORed with the Common IV.
     */
    internal fun nonce(idPiv: ByteArray, partialIv: ByteArray): ByteArray {
        val length = commonIv.size
        val nonce = ByteArray(length)
        nonce[0] = idPiv.size.toByte()
        idPiv.copyInto(nonce, destinationOffset = length - 5 - idPiv.size)
        partialIv.copyInto(nonce, destinationOffset = length - partialIv.size)
        for (i in 0 until length) nonce[i] = (nonce[i].toInt() xor commonIv[i].toInt()).toByte()
        return nonce
    }

    override fun toString(): String = "SecurityContext(${parameters.name})"

    public companion object {
        /** RFC 8613 §7.2.1: Sender Sequence Numbers stay below 2^40, the 5-byte Partial IV range. */
        public const val MAX_SEQUENCE_NUMBER: Long = (1L shl 40) - 1

        /**
         * Derives Sender Key, Recipient Key and Common IV with HKDF (RFC 8613 §3.2.1) and restores the
         * Sender Sequence Number from [sequenceStore].
         *
         * @param persistInterval how many sequence numbers to reserve per write to [sequenceStore].
         */
        public suspend fun derive(
            parameters: SecurityContextParameters,
            crypto: CryptoBackend,
            sequenceStore: SequenceNumberStore = InMemorySequenceNumberStore(),
            persistInterval: Long = 64,
        ): SecurityContext {
            require(persistInterval > 0) { "persistInterval must be positive" }
            val hkdf = Hkdf(crypto, parameters.hkdfAlgorithm)
            val prk = hkdf.extract(parameters.masterSalt, parameters.masterSecret)
            val aead = parameters.aeadAlgorithm
            val senderKey = hkdf.expand(prk, info(parameters, parameters.senderId, "Key", aead.keyLength), aead.keyLength)
            val recipientKey = hkdf.expand(prk, info(parameters, parameters.recipientId, "Key", aead.keyLength), aead.keyLength)
            val commonIv = hkdf.expand(prk, info(parameters, ByteArray(0), "IV", aead.nonceLength), aead.nonceLength)
            val start = sequenceStore.load(parameters.name) ?: 0
            return SecurityContext(
                parameters = parameters,
                senderKey = senderKey,
                recipientKey = recipientKey,
                commonIv = commonIv,
                senderAead = crypto.aead(aead, senderKey),
                recipientAead = crypto.aead(aead, recipientKey),
                sequenceStore = sequenceStore,
                nextSequenceNumber = start,
                persistInterval = persistInterval,
            )
        }

        /** `info = [ id, id_context / nil, alg_aead, type, L ]` (RFC 8613 §3.2.1). */
        internal fun info(parameters: SecurityContextParameters, id: ByteArray, type: String, length: Int): ByteArray {
            val writer = CborWriter().writeArrayHeader(5).writeBytes(id)
            val idContext = parameters.idContext
            if (idContext == null) writer.writeNull() else writer.writeBytes(idContext)
            return writer.writeInt(parameters.aeadAlgorithm.coseId).writeText(type).writeInt(length).toByteArray()
        }
    }
}

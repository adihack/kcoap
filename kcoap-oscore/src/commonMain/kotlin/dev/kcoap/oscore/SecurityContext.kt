package dev.kcoap.oscore

import dev.kcoap.cbor.CborWriter
import dev.kcoap.common.constantTimeEquals
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
    private val crypto: CryptoBackend,
    /** False until a request proved its freshness with Echo, when Appendix B.1.2 applies. */
    private var isFresh: Boolean,
) {
    private val senderLock = Mutex()
    private val recipientLock = Mutex()

    /** Numbers below this limit are durable in [sequenceStore]; only those may be handed out. */
    private var persistedLimit: Long = nextSequenceNumber
    private val replayWindow = ReplayWindow(parameters.replayWindowSize)

    public val senderId: ByteArray get() = parameters.senderId
    public val recipientId: ByteArray get() = parameters.recipientId
    public val idContext: ByteArray? get() = parameters.idContext

    /** The next Sender Sequence Number this context will use. */
    public suspend fun peekSequenceNumber(): Long = senderLock.withLock { nextSequenceNumber }

    /**
     * Takes the next Sender Sequence Number, persisting a new block first when needed (Appendix B.1.1).
     *
     * The new limit is committed only after [SequenceNumberStore.store] returned. If the write throws or
     * is cancelled, nothing changes here and the next call writes again, so no number is ever handed out
     * at or above a limit that is not durable.
     */
    internal suspend fun takeSequenceNumber(): Long = senderLock.withLock {
        val number = nextSequenceNumber
        if (number > MAX_SEQUENCE_NUMBER) throw OscoreException(OscoreError.SEQUENCE_EXHAUSTED)
        if (number >= persistedLimit) {
            val limit = minOf(number + persistInterval, MAX_SEQUENCE_NUMBER + 1)
            sequenceStore.store(parameters.name, limit)
            persistedLimit = limit
        }
        nextSequenceNumber = number + 1
        number
    }

    /** The Echo value of an outstanding freshness challenge (Appendix B.1.2). */
    private var pendingEcho: ByteArray? = null

    /** Whether [sequence] from the peer was already accepted (or is too old for the window). */
    internal suspend fun isReplay(sequence: Long): Boolean = recipientLock.withLock { replayWindow.isReplay(sequence) }

    /** What to do with an authenticated request. */
    internal sealed interface Admission {
        data object Accepted : Admission
        data object Replay : Admission

        /** Appendix B.1.2: answer with a protected 4.01 carrying [echo], and do not process the request. */
        class Challenge(val echo: ByteArray) : Admission
    }

    /**
     * Admits an authenticated request with Partial IV [sequence] and inner Echo [echo] (RFC 8613 §7.4,
     * Appendix B.1.2). Checking and updating the replay window is atomic.
     */
    internal suspend fun admit(sequence: Long, echo: ByteArray?): Admission = recipientLock.withLock {
        when {
            isFresh -> if (replayWindow.accept(sequence)) Admission.Accepted else Admission.Replay
            echo != null && pendingEcho?.let { constantTimeEquals(it, echo) } == true -> {
                replayWindow.initializeAt(sequence)
                isFresh = true
                pendingEcho = null
                Admission.Accepted
            }
            else -> Admission.Challenge(crypto.randomBytes(ECHO_LENGTH).also { pendingEcho = it })
        }
    }

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

        /** Length of the Echo values of freshness challenges; RFC 9175 §2.2 allows up to 40 bytes. */
        private const val ECHO_LENGTH = 8

        /**
         * Derives Sender Key, Recipient Key and Common IV with HKDF (RFC 8613 §3.2.1) and restores the
         * Sender Sequence Number from [sequenceStore] (Appendix B.1.1).
         *
         * [sequenceStore] has no default, because the safe choice depends on where the keys come from:
         *
         * - A provisioned Master Secret that outlives the process needs a durable store. With
         *   [EphemeralSequenceNumberStore] a restarted process starts at sequence number 0 again and
         *   reuses AEAD nonces, which breaks the confidentiality and integrity of AES-CCM.
         * - Keys that are new in this process run, such as EDHOC output, use
         *   [EphemeralSequenceNumberStore].
         *
         * @param sequenceStore where the Sender Sequence Number is persisted; see [SequenceNumberStore].
         * @param persistInterval how many sequence numbers to reserve per write to [sequenceStore] (K in
         *   Appendix B.1.1), from 1 to 2^40. A restart skips at most this many numbers.
         * @param freshness whether the replay window is trusted from the start or only after a request
         *   proved its freshness with Echo (Appendix B.1.2). [FreshnessPolicy.AUTO] requires the proof
         *   for a context restored from [sequenceStore] and for any store but the ephemeral one.
         * @throws IllegalArgumentException when [persistInterval] is out of range, or [sequenceStore]
         *   returns a value outside 0 to 2^40. Exceptions of [sequenceStore] reach the caller unchanged.
         */
        public suspend fun derive(
            parameters: SecurityContextParameters,
            crypto: CryptoBackend,
            sequenceStore: SequenceNumberStore,
            persistInterval: Long = 64,
            freshness: FreshnessPolicy = FreshnessPolicy.AUTO,
        ): SecurityContext {
            require(persistInterval in 1..MAX_SEQUENCE_NUMBER + 1) { "persistInterval must be in 1..2^40" }
            val hkdf = Hkdf(crypto, parameters.hkdfAlgorithm)
            val prk = hkdf.extract(parameters.masterSalt, parameters.masterSecret)
            val aead = parameters.aeadAlgorithm
            val senderKey = hkdf.expand(prk, info(parameters, parameters.senderId, "Key", aead.keyLength), aead.keyLength)
            val recipientKey = hkdf.expand(prk, info(parameters, parameters.recipientId, "Key", aead.keyLength), aead.keyLength)
            val commonIv = hkdf.expand(prk, info(parameters, ByteArray(0), "IV", aead.nonceLength), aead.nonceLength)
            val senderAead = crypto.aead(aead, senderKey)
            val recipientAead = crypto.aead(aead, recipientKey)

            val name = parameters.name
            val restored = sequenceStore.load(name)
            require(restored == null || restored in 0..MAX_SEQUENCE_NUMBER + 1) {
                "$sequenceStore returned $restored for $name, outside 0..2^40"
            }
            // Recorded, so that deriving this context again from the same store counts as a restore.
            if (restored == null) sequenceStore.store(name, 0)
            val isFresh = when (freshness) {
                FreshnessPolicy.AUTO -> restored == null && sequenceStore is EphemeralSequenceNumberStore
                FreshnessPolicy.REQUIRE_PROOF -> false
                FreshnessPolicy.ASSUME_FRESH -> true
            }
            return SecurityContext(
                parameters = parameters,
                senderKey = senderKey,
                recipientKey = recipientKey,
                commonIv = commonIv,
                senderAead = senderAead,
                recipientAead = recipientAead,
                sequenceStore = sequenceStore,
                nextSequenceNumber = restored ?: 0,
                persistInterval = persistInterval,
                crypto = crypto,
                isFresh = isFresh,
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

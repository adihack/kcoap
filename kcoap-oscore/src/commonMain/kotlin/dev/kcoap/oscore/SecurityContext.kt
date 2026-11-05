package dev.kcoap.oscore

import dev.kcoap.cbor.CborWriter
import dev.kcoap.coap.security.SecurityIdentity
import dev.kcoap.common.constantTimeEquals
import dev.kcoap.common.wipe
import dev.kcoap.crypto.Aead
import dev.kcoap.crypto.AeadAlgorithm
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
 * The derived Sender and Recipient Keys and the Common IV never leave the object. The input parameters,
 * Master Secret included, stay inside the module for re-deriving the context and are not reachable from
 * here; `toString()` shows only identifiers.
 *
 * Use one instance per set of keys and share it. Two live instances derived from the same parameters
 * hand out the same sequence numbers, so their nonces repeat (see [derive]).
 */
public class SecurityContext private constructor(
    internal val parameters: SecurityContextParameters,
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
    identity: SecurityIdentity?,
) {
    private val senderLock = Mutex()
    private val recipientLock = Mutex()

    /** Numbers below this limit are durable in [sequenceStore]; only those may be handed out. */
    private var persistedLimit: Long = nextSequenceNumber
    private val replayWindow = ReplayWindow(parameters.replayWindowSize)

    /** The name the context is persisted and logged under: its identifiers, never a secret. */
    public val name: String = parameters.name

    /** The AEAD algorithm that protects messages (RFC 8613 §3.1). */
    public val aeadAlgorithm: AeadAlgorithm get() = parameters.aeadAlgorithm

    /** This endpoint's Sender ID (RFC 8613 §3.1), a copy. */
    public val senderId: ByteArray get() = parameters.senderId

    /** The peer's Sender ID, which selects this context for its requests (§3.1), a copy. */
    public val recipientId: ByteArray get() = parameters.recipientId

    /** The ID Context, or null when none is used (§3.1), a copy. */
    public val idContext: ByteArray? get() = parameters.idContext

    /**
     * Who the peer of this context is, for authorization: the identity of every request this context verifies
     * (`UnprotectedRequest.identity`, which access policies and handlers see; ADR 0008). By default the
     * [OscoreIdentity] of the Recipient ID and ID Context. A context that EDHOC established carries
     * kcoap-edhoc-oscore's `EdhocIdentity`, with the peer's authenticated credential, and a provisioned one may
     * carry a name of the application's ([derive]'s `identity`). It belongs to this object, not to the Recipient
     * ID: a context that later takes the same Recipient ID, such as one of an EDHOC session that reuses a
     * released C_R, has its own.
     */
    public val identity: SecurityIdentity = identity ?: OscoreIdentity(parameters.recipientId, parameters.idContext)

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
            sequenceStore.store(name, limit)
            persistedLimit = limit
        }
        nextSequenceNumber = number + 1
        number
    }

    /**
     * The Echo value of the outstanding freshness challenge (Appendix B.1.2, RFC 9175 §2.3), kept until a
     * request returns it. Drawing a new value for every challenge would let an attacker who replays one
     * recorded request revoke the value the legitimate client is about to send back, so the proof would
     * never complete. Repeating the value reveals nothing: it travels only inside OSCORE-protected 4.01
     * responses.
     */
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
            else -> {
                val outstanding = pendingEcho ?: crypto.randomBytes(ECHO_LENGTH).also { pendingEcho = it }
                Admission.Challenge(outstanding.copyOf())
            }
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

    override fun toString(): String = "SecurityContext($name)"

    /** The output of the key derivation (RFC 8613 §3.2.1), which the Appendix C vectors check. */
    internal class DerivedKeys(val senderKey: ByteArray, val recipientKey: ByteArray, val commonIv: ByteArray) {
        /** Overwrites all three, for keys that no context took over. */
        fun wipe() {
            senderKey.wipe()
            recipientKey.wipe()
            commonIv.wipe()
        }
    }

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
         * At most one live context may exist per store and context [name][SecurityContextParameters.name].
         * Deriving again from the same store, after a restart or within the process, is a restore: it is
         * safe only once the previous context for these parameters is discarded and no call is still
         * running on it. A context still in use goes on handing out numbers from the block it reserved, and
         * the new one reserves the next block, which the old one reaches later: the same nonce under the
         * same Sender Key. Two derivations that run at the same time both find no value and both start at
         * 0. The library does not detect either case.
         *
         * The store is read, and a new context recorded, before any key exists, so a failing store leaves
         * no key material behind. When the backend cannot build the AEAD, the derived keys and the Common
         * IV are wiped before the exception reaches the caller.
         *
         * @param sequenceStore where the Sender Sequence Number is persisted; see [SequenceNumberStore].
         * @param persistInterval how many sequence numbers to reserve per write to [sequenceStore] (K in
         *   Appendix B.1.1), from 1 to 2^40. A restart skips at most this many numbers.
         * @param freshness whether the replay window is trusted from the start or only after a request
         *   proved its freshness with Echo (Appendix B.1.2). [FreshnessPolicy.AUTO] requires the proof
         *   for a context restored from [sequenceStore] and for any store but the ephemeral one.
         * @param identity who the peer of the context is, for authorization ([SecurityContext.identity]); null
         *   for its [OscoreIdentity].
         * @throws IllegalArgumentException when [persistInterval] is out of range, or [sequenceStore]
         *   returns a value outside 0 to 2^40. Exceptions of [sequenceStore] and of [crypto] reach the
         *   caller unchanged. When the first write for a new context throws after it became durable, the
         *   next derivation counts as a restore.
         */
        public suspend fun derive(
            parameters: SecurityContextParameters,
            crypto: CryptoBackend,
            sequenceStore: SequenceNumberStore,
            persistInterval: Long = 64,
            freshness: FreshnessPolicy = FreshnessPolicy.AUTO,
            identity: SecurityIdentity? = null,
        ): SecurityContext {
            require(persistInterval in 1..MAX_SEQUENCE_NUMBER + 1) { "persistInterval must be in 1..2^40" }
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

            val keys = deriveKeys(parameters, crypto)
            val algorithm = parameters.aeadAlgorithm
            val senderAead: Aead
            val recipientAead: Aead
            try {
                // The backend owns the key arrays from here on: its AEAD objects may keep them.
                senderAead = crypto.aead(algorithm, keys.senderKey)
                recipientAead = crypto.aead(algorithm, keys.recipientKey)
            } catch (e: Throwable) {
                // No context takes the keys over; an AEAD built before the failure is dropped with them.
                keys.wipe()
                throw e
            }
            return SecurityContext(
                parameters = parameters,
                commonIv = keys.commonIv,
                senderAead = senderAead,
                recipientAead = recipientAead,
                sequenceStore = sequenceStore,
                nextSequenceNumber = restored ?: 0,
                persistInterval = persistInterval,
                crypto = crypto,
                isFresh = isFresh,
                identity = identity,
            )
        }

        /**
         * HKDF-Extract over the Master Secret and Salt, then HKDF-Expand to the two keys and the Common IV
         * (RFC 8613 §3.2.1). The copies of the Master Secret and Salt and the PRK are wiped afterwards, and
         * so is every key already expanded when a later step fails.
         */
        internal suspend fun deriveKeys(parameters: SecurityContextParameters, crypto: CryptoBackend): DerivedKeys {
            val hkdf = Hkdf(crypto, parameters.hkdfAlgorithm)
            val secret = parameters.masterSecret()
            val salt = parameters.masterSalt()
            val prk = try {
                hkdf.extract(salt, secret)
            } finally {
                secret.fill(0)
                salt.fill(0)
            }
            val expanded = mutableListOf<ByteArray>()
            suspend fun expand(id: ByteArray, type: String, length: Int): ByteArray =
                hkdf.expand(prk, info(parameters, id, type, length), length).also { expanded += it }
            try {
                val aead = parameters.aeadAlgorithm
                return DerivedKeys(
                    senderKey = expand(parameters.senderId, "Key", aead.keyLength),
                    recipientKey = expand(parameters.recipientId, "Key", aead.keyLength),
                    commonIv = expand(ByteArray(0), "IV", aead.nonceLength),
                )
            } catch (e: Throwable) {
                expanded.forEach { it.wipe() }
                throw e
            } finally {
                prk.wipe()
            }
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

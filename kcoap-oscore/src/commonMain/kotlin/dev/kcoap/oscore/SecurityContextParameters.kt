package dev.kcoap.oscore

import dev.kcoap.common.toHex
import dev.kcoap.crypto.AeadAlgorithm
import dev.kcoap.crypto.HashAlgorithm

/**
 * The input parameters of an OSCORE security context (RFC 8613 §3.2), as agreed out of band or derived
 * by EDHOC. [SecurityContext.derive] turns them into keys.
 *
 * @param masterSecret the Master Secret; required.
 * @param senderId this endpoint's Sender ID; the empty string is legitimate.
 * @param recipientId the peer's Sender ID.
 * @param masterSalt the Master Salt; empty by default.
 * @param idContext the ID Context, or null when none is used.
 * @param includeIdContext whether requests carry the ID Context as 'kid context' (RFC 8613 §5.1), for
 *   servers that select contexts by it.
 * @param replayWindowSize the anti-replay window, 32 by default (§3.2.2); at most 64.
 */
public class SecurityContextParameters(
    masterSecret: ByteArray,
    senderId: ByteArray,
    recipientId: ByteArray,
    masterSalt: ByteArray = ByteArray(0),
    idContext: ByteArray? = null,
    public val aeadAlgorithm: AeadAlgorithm = AeadAlgorithm.AES_CCM_16_64_128,
    public val hkdfAlgorithm: HashAlgorithm = HashAlgorithm.SHA_256,
    public val includeIdContext: Boolean = false,
    public val replayWindowSize: Int = 32,
) {
    private val secret = masterSecret.copyOf()
    private val salt = masterSalt.copyOf()
    private val sender = senderId.copyOf()
    private val recipient = recipientId.copyOf()
    private val context = idContext?.copyOf()

    init {
        require(secret.isNotEmpty()) { "The Master Secret must not be empty" }
        // RFC 8613 §3.3: IDs are at most nonce length minus 6 bytes, so they fit the nonce (§5.2).
        val maxIdLength = aeadAlgorithm.nonceLength - 6
        require(sender.size <= maxIdLength) { "Sender ID longer than $maxIdLength bytes" }
        require(recipient.size <= maxIdLength) { "Recipient ID longer than $maxIdLength bytes" }
        require(!sender.contentEquals(recipient)) { "Sender and Recipient ID must differ, or keys and nonces repeat" }
        require(!hkdfAlgorithm.isXof) { "OSCORE uses an HMAC-based HKDF (RFC 8613 §3.2.1)" }
        require(replayWindowSize in 1..64) { "Replay window size must be 1..64" }
        require(!includeIdContext || context != null) { "includeIdContext needs an ID Context" }
    }

    /**
     * A copy of the Master Secret, for provisioning and persistence code. A function, so every copy of the
     * secret shows at the call site; wipe the copy when done with it.
     */
    public fun masterSecret(): ByteArray = secret.copyOf()

    /** A copy of the Master Salt; secret-derived when EDHOC exported it (RFC 9528 Appendix A.1). */
    public fun masterSalt(): ByteArray = salt.copyOf()

    public val senderId: ByteArray get() = sender.copyOf()
    public val recipientId: ByteArray get() = recipient.copyOf()
    public val idContext: ByteArray? get() = context?.copyOf()

    /** A stable name for persistence and logs: the IDs, never the secret. */
    public val name: String get() = "sid=${sender.toHex()},rid=${recipient.toHex()}" + (context?.let { ",idc=${it.toHex()}" } ?: "")

    override fun toString(): String = "SecurityContextParameters($name, $aeadAlgorithm)"
}

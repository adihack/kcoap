package dev.kcoap.edhoc.oscore

import dev.kcoap.common.wipe
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.FreshnessPolicy
import dev.kcoap.oscore.OscoreIdentity
import dev.kcoap.oscore.SecurityContext
import dev.kcoap.oscore.SecurityContextParameters
import dev.kcoap.oscore.SequenceNumberStore

/**
 * The OSCORE security context parameters an EDHOC session establishes (RFC 9528 Appendix A.1):
 *
 * - Master Secret = EDHOC_Exporter(0, h'', key length of the application AEAD)
 * - Master Salt = EDHOC_Exporter(1, h'', 8)
 * - AEAD and HKDF from the application algorithms of the selected cipher suite
 * - Sender ID = the peer's connection identifier, Recipient ID = this side's own (Table 14)
 *
 * The parameters hold copies of the exported Master Secret and Salt; the exporter outputs themselves are
 * wiped, also when the second export fails.
 *
 * Persisting the parameters to resume the context after a restart is safe only when the context had a
 * durable [SequenceNumberStore] from its first derivation, that is when it came from
 * [oscoreContext]`(crypto, durableStore)`, or from an [EdhocServer] or [EdhocClientHandshake] given that
 * store as its `sequenceStore`. After the restart, derive it from the persisted parameters with
 * [SecurityContext.derive] and that same store, which resumes above every sequence number used before and
 * requires the Echo proof for requests (RFC 8613 Appendix B.1). A context that used the ephemeral store,
 * the default of all three, left no record of its sequence numbers: derived again, it would start at 0
 * and reuse AEAD nonces (RFC 8613 Appendix B.1.1). Run EDHOC again instead.
 *
 * @param keyLength the OSCORE key length, by default that of the application AEAD.
 * @param saltLength the OSCORE Master Salt length, 8 by default.
 */
public suspend fun EdhocSession.oscoreParameters(
    keyLength: Int = cipherSuite.appAead.keyLength,
    saltLength: Int = DEFAULT_SALT_LENGTH,
): SecurityContextParameters = oscoreParametersFrom(
    exportMasterSecret = { oscoreMasterSecret(keyLength) },
    exportMasterSalt = { oscoreMasterSalt(saltLength) },
)

/**
 * [oscoreParameters] over the two exports, which this function owns and wipes: the parameters keep their
 * own copies. A seam for the tests, which see the exported arrays here.
 */
internal suspend fun EdhocSession.oscoreParametersFrom(
    exportMasterSecret: suspend () -> ByteArray,
    exportMasterSalt: suspend () -> ByteArray,
): SecurityContextParameters {
    require(!cipherSuite.appHash.isXof) {
        "Suite ${cipherSuite.value} has no HMAC-based application hash, which OSCORE's HKDF needs"
    }
    val masterSecret = exportMasterSecret()
    var masterSalt: ByteArray? = null
    try {
        masterSalt = exportMasterSalt()
        return SecurityContextParameters(
            masterSecret = masterSecret,
            senderId = peerConnectionIdentifier.bytes,
            recipientId = ownConnectionIdentifier.bytes,
            masterSalt = masterSalt,
            aeadAlgorithm = cipherSuite.appAead,
            hkdfAlgorithm = cipherSuite.appHash,
        )
    } finally {
        masterSecret.wipe()
        masterSalt?.wipe()
    }
}

/**
 * Derives the OSCORE security context of this EDHOC session (RFC 9528 Appendix A.1).
 *
 * The keys are new with every session, so no request under them can have been accepted before: the
 * replay window is trusted from the start ([FreshnessPolicy.ASSUME_FRESH]), whatever [sequenceStore] is.
 * Without that, a durable store would make [FreshnessPolicy.AUTO] challenge the first request, which
 * breaks the combined EDHOC + OSCORE request of RFC 9668.
 *
 * Call it once per session. [EdhocServer] and [EdhocClientHandshake] already do, with their
 * `sequenceStore`, and return or register the result; deriving a second context from the same session gives two
 * contexts with the same keys that both start at sequence number 0, so their nonces repeat. Only an
 * application that drives [dev.kcoap.edhoc.EdhocInitiator] or [dev.kcoap.edhoc.EdhocResponder] itself
 * derives the context here.
 *
 * Sequence numbers live in memory ([EphemeralSequenceNumberStore]) unless [sequenceStore] says
 * otherwise, and such a context ends with the process. To resume one after a restart, pass a durable
 * store here, persist [oscoreParameters], and derive again from both as [oscoreParameters] describes, with the
 * [SecurityContext.identity] of this one, which the parameters do not hold.
 *
 * The context's [SecurityContext.identity] is an [EdhocIdentity]: the peer's authenticated credential and the
 * context's Recipient ID, which the requests it verifies carry to access policies and handlers (ADR 0008).
 */
public suspend fun EdhocSession.oscoreContext(
    crypto: CryptoBackend,
    sequenceStore: SequenceNumberStore = EphemeralSequenceNumberStore(),
    keyLength: Int = cipherSuite.appAead.keyLength,
    saltLength: Int = DEFAULT_SALT_LENGTH,
): SecurityContext = SecurityContext.derive(
    oscoreParameters(keyLength, saltLength),
    crypto,
    sequenceStore = sequenceStore,
    freshness = FreshnessPolicy.ASSUME_FRESH,
    identity = EdhocIdentity(OscoreIdentity(ownConnectionIdentifier.bytes, null), peerCredential, peerCredentialIdentifier),
)

/** RFC 9528 Appendix A.1: the default oscore_salt_length. */
private const val DEFAULT_SALT_LENGTH = 8

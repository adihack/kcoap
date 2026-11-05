package dev.kcoap.edhoc.oscore

import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.oscore.EphemeralSequenceNumberStore
import dev.kcoap.oscore.FreshnessPolicy
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
 * An application that persists the parameters, to resume the context after a restart, derives it with
 * [SecurityContext.derive] and a durable [SequenceNumberStore], never with the ephemeral one.
 *
 * @param keyLength the OSCORE key length, by default that of the application AEAD.
 * @param saltLength the OSCORE Master Salt length, 8 by default.
 */
public suspend fun EdhocSession.oscoreParameters(
    keyLength: Int = cipherSuite.appAead.keyLength,
    saltLength: Int = DEFAULT_SALT_LENGTH,
): SecurityContextParameters {
    require(!cipherSuite.appHash.isXof) {
        "Suite ${cipherSuite.value} has no HMAC-based application hash, which OSCORE's HKDF needs"
    }
    return SecurityContextParameters(
        masterSecret = oscoreMasterSecret(keyLength),
        senderId = peerConnectionIdentifier.bytes,
        recipientId = ownConnectionIdentifier.bytes,
        masterSalt = oscoreMasterSalt(saltLength),
        aeadAlgorithm = cipherSuite.appAead,
        hkdfAlgorithm = cipherSuite.appHash,
    )
}

/**
 * Derives the OSCORE security context of this EDHOC session (RFC 9528 Appendix A.1).
 *
 * The keys are new with every session, so no request under them can have been accepted before: the
 * replay window is trusted from the start ([FreshnessPolicy.ASSUME_FRESH]), and sequence numbers live in
 * memory ([EphemeralSequenceNumberStore]) unless [sequenceStore] says otherwise. To use the context
 * again after a restart, persist [oscoreParameters] and derive it with a durable store instead.
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
)

/** RFC 9528 Appendix A.1: the default oscore_salt_length. */
private const val DEFAULT_SALT_LENGTH = 8

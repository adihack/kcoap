package dev.kcoap.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import dev.kcoap.crypto.CryptoBackend
import kotlin.test.assertIs

/**
 * Derives a context whose keys are new in this test, the case [EphemeralSequenceNumberStore] is for, so
 * the tests that are not about persistence state that choice in one place.
 */
internal suspend fun ephemeralContext(
    parameters: SecurityContextParameters,
    crypto: CryptoBackend,
    freshness: FreshnessPolicy = FreshnessPolicy.AUTO,
): SecurityContext = SecurityContext.derive(parameters, crypto, EphemeralSequenceNumberStore(), freshness = freshness)

/**
 * Derives the context of an endpoint after a restart: its store already holds the limit [persisted] from
 * the previous run, so [FreshnessPolicy.AUTO] requires the RFC 8613 Appendix B.1.2 proof.
 */
internal suspend fun restoredContext(
    parameters: SecurityContextParameters,
    crypto: CryptoBackend,
    persisted: Long = 1_000,
): SecurityContext {
    val store = EphemeralSequenceNumberStore().apply { store(parameters.name, persisted) }
    return SecurityContext.derive(parameters, crypto, store)
}

/**
 * The response [response] verified to ([ProtectedRequest.unprotectResponse]), failing the test when it was handed
 * on unverified; a response that fails verification throws [dev.kcoap.coap.security.MessageSecurityException].
 */
internal suspend fun ProtectedRequest.verified(response: CoapMessage): CoapMessage =
    assertIs<ResponseVerification.Verified>(unprotectResponse(response), "verified").message

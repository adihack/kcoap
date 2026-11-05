package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ResponseVerification
import kotlin.test.assertIs

/**
 * The response [response] verified to ([ProtectedRequest.unprotectResponse]), failing the test when it was handed
 * on unverified.
 */
internal suspend fun ProtectedRequest.verified(response: CoapMessage): CoapMessage =
    assertIs<ResponseVerification.Verified>(unprotectResponse(response), "verified").message

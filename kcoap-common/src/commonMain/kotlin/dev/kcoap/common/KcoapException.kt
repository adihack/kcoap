package dev.kcoap.common

/**
 * The root of the exceptions kcoap's facades fail with at runtime: what went wrong with the peer, the network,
 * message security, the protocol or the endpoint's lifecycle, as opposed to a mistake of the caller, which is an
 * `IllegalArgumentException` or `IllegalStateException`. Each protocol has its own subtype, which says what failed
 * and carries what that protocol needs to answer:
 *
 * | Module | Subtype |
 * |---|---|
 * | kcoap-coap | `CoapException`, with its `Reason` (RFC 7252, RFC 8323) |
 * | kcoap-oscore | `OscoreException`, with its `OscoreError` (RFC 8613 §8.2, §7.4) |
 * | kcoap-edhoc | `EdhocException`, with its `EdhocError` (RFC 9528 §6), and `EdhocPeerErrorException` |
 * | kcoap-edhoc-oscore | `EdhocTransferException`, an `EdhocException` (RFC 9528 Appendix A.2) |
 * | kcoap-cbor | `CborException` (RFC 8949), also for COSE structures (RFC 9052) |
 * | kcoap-crypto | `CertificateFormatException` (RFC 5280 §4.1) |
 *
 * Catching `KcoapException` handles every such failure of any kcoap call at once; catching the subtype handles
 * those of one protocol. The signals of the SPIs between the layers stay outside this root, since each layer
 * translates them at its boundary: `MessageSecurityException`, `CoapFormatException`, `SessionEndedException`,
 * `AeadAuthenticationException`, `UnsupportedAlgorithmException` and `CertificateValidationException`.
 *
 * **Swift and Objective-C.** The public suspend entry points (the `CoapClient` requests, `discover` and `ping`,
 * the EDHOC steps, `SecurityContext.derive` and the others) list this class and `CancellationException` in
 * `@Throws`, so on Apple platforms such a failure reaches Swift as an `NSError` that a `do`/`catch` handles, with
 * the Kotlin exception in its `userInfo` under the key `"KotlinException"`. An exception of a type the function
 * does not list, such as the `IllegalArgumentException` of a mistake of the caller, still ends the process there,
 * as a failed precondition does in Swift. `CoapUri.parse`, whose input is often typed in, lists
 * `IllegalArgumentException` so that Swift can catch a malformed URI.
 *
 * The class is open with a protected constructor, which takes the [cause] or null, for kcoap's protocol modules.
 * Nothing stops another module from extending it, but applications should not, so that catching `KcoapException`
 * means a failure of kcoap.
 */
public open class KcoapException protected constructor(message: String, cause: Throwable?) : Exception(message, cause)

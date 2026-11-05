package dev.kcoap.sample.android

import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyType
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.CredentialIdentifier
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocMethod
import dev.kcoap.edhoc.LocalCredential
import dev.kcoap.edhoc.TrustedCredentials

/**
 * The application profile of an EDHOC Initiator (RFC 9528 §3.9): signatures on both sides (method 0) in
 * cipher suite 2, which every platform runs (P-256, ES256, AES-CCM-16-64-128, SHA-256), with a fresh
 * P-256 key in a CCS credential identified by 'kid' (RFC 9528 §3.5.2). The Responder's credential would
 * be pinned in [TrustedCredentials]; this sample has none to pin.
 *
 * [EdhocInitiatorConfig.supportedBy] narrows the configuration to the suites [crypto] runs, and throws
 * when none is left, so a device that cannot run it fails here rather than in a handshake.
 */
internal suspend fun edhocInitiatorConfig(crypto: CryptoBackend): EdhocInitiatorConfig {
    val kid = byteArrayOf(0x2b)
    val keyPair = crypto.generateKeyPair(KeyType.P256)
    // An uncompressed SEC1 point, 04 || x || y.
    val point = keyPair.publicKey
    val x = point.copyOfRange(1, 33)
    val y = point.copyOfRange(33, 65)
    val coseKey = CoseKey(CoseKeyType.EC2, CoseCurve.P_256, x, y, kid = kid)
    val credential = LocalCredential.signature(
        AuthenticationCredential.ccs(CwtClaimsSet("android-consumer", coseKey)),
        CredentialIdentifier.KeyId(kid),
        keyPair.privateKey,
    )
    return EdhocInitiatorConfig(
        method = EdhocMethod.SIGNATURE_SIGNATURE,
        cipherSuites = listOf(CipherSuite.SUITE_2),
        credentials = listOf(credential),
        credentialResolver = TrustedCredentials(crypto, emptyList()),
    ).supportedBy(crypto)
}

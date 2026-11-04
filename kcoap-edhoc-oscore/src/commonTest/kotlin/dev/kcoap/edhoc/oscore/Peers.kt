package dev.kcoap.edhoc.oscore

import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyType
import dev.kcoap.edhoc.AuthenticationCredential
import dev.kcoap.edhoc.AuthenticationKind
import dev.kcoap.edhoc.CipherSuite
import dev.kcoap.edhoc.ConnectionIdentifierProvider
import dev.kcoap.edhoc.CredentialIdentifier
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocMethod
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.LocalCredential
import dev.kcoap.edhoc.TrustedCredentials

/** A client and a server that trust each other's Ed25519 CCS credentials, for EDHOC method 0. */
internal class Peers private constructor(
    val client: LocalCredential,
    val server: LocalCredential,
    private val backend: CryptoBackend,
) {
    fun initiatorConfig(
        suites: List<CipherSuite> = listOf(CipherSuite.SUITE_0),
        connectionIdentifiers: ConnectionIdentifierProvider = ConnectionIdentifierProvider.Random,
    ) = EdhocInitiatorConfig(
        method = EdhocMethod.SIGNATURE_SIGNATURE,
        cipherSuites = suites,
        credentials = listOf(client),
        credentialResolver = TrustedCredentials(backend, listOf(server.credential)),
        connectionIdentifiers = connectionIdentifiers,
    )

    fun responderConfig(suites: List<CipherSuite> = listOf(CipherSuite.SUITE_0)) = EdhocResponderConfig(
        methods = setOf(EdhocMethod.SIGNATURE_SIGNATURE),
        cipherSuites = suites,
        credentials = listOf(server),
        credentialResolver = TrustedCredentials(backend, listOf(client.credential)),
    )

    companion object {
        suspend fun create(backend: CryptoBackend): Peers =
            Peers(credential(backend, "client", byteArrayOf(0x0c)), credential(backend, "server", byteArrayOf(0x05)), backend)

        private suspend fun credential(backend: CryptoBackend, subject: String, kid: ByteArray): LocalCredential {
            val pair = backend.generateKeyPair(KeyType.ED25519)
            val key = CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, pair.publicKey, kid = kid)
            return LocalCredential(
                AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
                CredentialIdentifier.KeyId(kid),
                pair.privateKey,
                AuthenticationKind.SIGNATURE,
            )
        }
    }
}

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
import dev.kcoap.edhoc.CredentialResolver
import dev.kcoap.edhoc.EadHandler
import dev.kcoap.edhoc.EdhocInitiatorConfig
import dev.kcoap.edhoc.EdhocMethod
import dev.kcoap.edhoc.EdhocResponderConfig
import dev.kcoap.edhoc.EphemeralKeySource
import dev.kcoap.edhoc.LocalCredential
import dev.kcoap.edhoc.TrustedCredentials

/**
 * A client and a server that trust each other's CCS credentials: Ed25519 for EDHOC method 0 ([create]), or
 * X25519 for method 3 ([staticDiffieHellman]).
 */
internal class Peers private constructor(
    val client: LocalCredential,
    val server: LocalCredential,
    private val method: EdhocMethod,
    private val backend: CryptoBackend,
) {
    fun initiatorConfig(
        suites: List<CipherSuite> = listOf(CipherSuite.SUITE_0),
        connectionIdentifiers: ConnectionIdentifierProvider = ConnectionIdentifierProvider.Random,
        ephemeralKeys: EphemeralKeySource = EphemeralKeySource.Random,
    ) = EdhocInitiatorConfig(
        method = method,
        cipherSuites = suites,
        credentials = listOf(client),
        credentialResolver = TrustedCredentials(backend, listOf(server.credential)),
        connectionIdentifiers = connectionIdentifiers,
        ephemeralKeys = ephemeralKeys,
    )

    fun responderConfig(
        suites: List<CipherSuite> = listOf(CipherSuite.SUITE_0),
        eadHandler: EadHandler = EadHandler.None,
        ephemeralKeys: EphemeralKeySource = EphemeralKeySource.Random,
        credentialResolver: CredentialResolver = trustingClient(),
    ) = EdhocResponderConfig(
        methods = setOf(method),
        cipherSuites = suites,
        credentials = listOf(server),
        credentialResolver = credentialResolver,
        eadHandler = eadHandler,
        ephemeralKeys = ephemeralKeys,
    )

    /** What the server trusts: the client's credential. */
    fun trustingClient(): CredentialResolver = TrustedCredentials(backend, listOf(client.credential))

    companion object {
        suspend fun create(backend: CryptoBackend): Peers = Peers(
            credential(backend, "client", byteArrayOf(0x0c), AuthenticationKind.SIGNATURE),
            credential(backend, "server", byteArrayOf(0x05), AuthenticationKind.SIGNATURE),
            EdhocMethod.SIGNATURE_SIGNATURE,
            backend,
        )

        /** Method 3: the Responder keeps its ephemeral key Y until message_3, for G_IY (RFC 9528 §4.1.1.3). */
        suspend fun staticDiffieHellman(backend: CryptoBackend): Peers = Peers(
            credential(backend, "client", byteArrayOf(0x0c), AuthenticationKind.STATIC_DH),
            credential(backend, "server", byteArrayOf(0x05), AuthenticationKind.STATIC_DH),
            EdhocMethod.STATIC_DH_STATIC_DH,
            backend,
        )

        private suspend fun credential(
            backend: CryptoBackend,
            subject: String,
            kid: ByteArray,
            kind: AuthenticationKind,
        ): LocalCredential {
            val (type, curve) = when (kind) {
                AuthenticationKind.SIGNATURE -> KeyType.ED25519 to CoseCurve.ED25519
                AuthenticationKind.STATIC_DH -> KeyType.X25519 to CoseCurve.X25519
            }
            val pair = backend.generateKeyPair(type)
            val key = CoseKey(CoseKeyType.OKP, curve, pair.publicKey, kid = kid)
            return LocalCredential(
                AuthenticationCredential.ccs(CwtClaimsSet(subject, key)),
                CredentialIdentifier.KeyId(kid),
                pair.privateKey,
                kind,
            )
        }
    }
}

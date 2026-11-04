package dev.kcoap.edhoc

import dev.kcoap.common.toHex
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyPair
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.SoftwarePrivateKey
import org.bouncycastle.crypto.generators.MLDSAKeyPairGenerator
import org.bouncycastle.crypto.params.MLDSAKeyGenerationParameters
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLDSAPublicKeyParameters
import java.security.SecureRandom

/**
 * An authentication identity for one side of an interop session, usable by kcoap ([local]) and describable to
 * the libedhoc peer as its own credential ([ownArguments]) or as the credential it must accept
 * ([peerArguments]). The key is generated fresh; libedhoc gets the private key in the encoding its reference
 * suites import, and the public key in the form its suite expects (`--peer-key` in edhoc_peer.c).
 */
internal class InteropIdentity private constructor(
    val local: LocalCredential,
    private val keyArgument: String,
    private val publicKeyForLibedhoc: ByteArray,
) {
    private val credential = local.credential
    private val isX509 = credential.certificate != null

    /** Arguments that make this identity the libedhoc peer's own. */
    val ownArguments: List<String>
        get() = listOf("--key", keyArgument) + if (isX509) {
            val chain = (local.identifier as CredentialIdentifier.X509Chain).certificates
            listOf("--own-x5chain", chain.joinToString(",") { it.toHex() })
        } else {
            listOf("--own-kid", credential.kid!!.toHex(), "--own-cred", credential.encoded.toHex())
        }

    /** Arguments that make the libedhoc peer accept this identity. */
    val peerArguments: List<String>
        get() = listOf("--peer-key", publicKeyForLibedhoc.toHex()) + if (isX509) {
            listOf("--peer-x5chain", credential.certificate!!.encoded.toHex())
        } else {
            listOf("--peer-kid", credential.kid!!.toHex(), "--peer-cred", credential.encoded.toHex())
        }

    companion object {
        /**
         * A fresh identity for [kind] in [suite]: a CCS referenced by [kid], or, with [x509], an X.509 chain sent
         * by value.
         */
        suspend fun create(
            backend: CryptoBackend,
            kind: AuthenticationKind,
            suite: CipherSuite,
            name: String,
            kid: ByteArray,
            x509: Boolean = false,
        ): InteropIdentity {
            val type = Fixtures.keyTypeFor(kind, suite)
            val pair = if (type == KeyType.ML_DSA_44) mlDsa44(backend) else backend.generateKeyPair(type)
            val privateKey = (pair.privateKey as SoftwarePrivateKey).encoded()
            val publicKey = pair.publicKey
            val (keyArgument, libedhocPublicKey) = when (type) {
                // Compact25519 signs with seed || public key.
                KeyType.ED25519 -> "ed25519:${(privateKey + publicKey).toHex()}" to publicKey
                KeyType.X25519 -> "x25519:${privateKey.toHex()}" to publicKey
                // Signature verification takes the uncompressed point, static DH the x-coordinate.
                KeyType.P256, KeyType.P384 -> {
                    val curve = if (type == KeyType.P256) "p256" else "p384"
                    val coordinate = (publicKey.size - 1) / 2
                    if (kind == AuthenticationKind.SIGNATURE) {
                        "$curve-sign:${privateKey.toHex()}" to publicKey
                    } else {
                        "$curve-dh:${privateKey.toHex()}" to publicKey.copyOfRange(1, 1 + coordinate)
                    }
                }
                KeyType.ML_DSA_44 -> "mldsa44:${privateKey.toHex()}" to publicKey
                else -> error("libedhoc has no $type keys")
            }
            val local = if (x509) {
                val oid = when (type) {
                    KeyType.ML_DSA_44 -> TestCertificates.ML_DSA_44
                    KeyType.ED25519 -> "2b6570"
                    else -> error("No test certificate for $type")
                }
                val leaf = TestCertificates.certificate(TestCertificates.algorithm(oid), publicKey, name)
                val rootKey = ByteArray(publicKey.size) { 1 }
                val root = TestCertificates.certificate(TestCertificates.algorithm(oid), rootKey, "root")
                val chain = CredentialIdentifier.X509Chain(listOf(leaf, root))
                LocalCredential(AuthenticationCredential.x509(leaf), chain, pair.privateKey, kind)
            } else {
                val claims = CwtClaimsSet(name, Fixtures.coseKey(type, publicKey, kid))
                val identifier = CredentialIdentifier.KeyId(kid)
                LocalCredential(AuthenticationCredential.ccs(claims), identifier, pair.privateKey, kind)
            }
            return InteropIdentity(local, keyArgument, libedhocPublicKey)
        }

        /**
         * An ML-DSA-44 key pair whose private key is the expanded FIPS 204 `sk`, which liboqs needs; the kcoap
         * backend accepts that encoding as well as the seed.
         */
        private suspend fun mlDsa44(backend: CryptoBackend): KeyPair {
            val generator = MLDSAKeyPairGenerator()
            generator.init(MLDSAKeyGenerationParameters(SecureRandom(), MLDSAParameters.ml_dsa_44))
            val pair = generator.generateKeyPair()
            val privateKey = (pair.private as MLDSAPrivateKeyParameters)
                .getParametersWithFormat(MLDSAPrivateKeyParameters.EXPANDED_KEY)
                .encoded
            val publicKey = (pair.public as MLDSAPublicKeyParameters).encoded
            return backend.importKeyPair(KeyType.ML_DSA_44, privateKey, publicKey)
        }
    }
}

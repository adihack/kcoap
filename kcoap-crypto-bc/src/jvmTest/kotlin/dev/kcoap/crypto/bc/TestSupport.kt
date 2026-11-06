package dev.kcoap.crypto.bc

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import dev.kcoap.crypto.CryptoBackend
import dev.kcoap.crypto.KeyType
import dev.kcoap.crypto.PrivateKey
import dev.kcoap.crypto.SoftwarePrivateKey
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import dev.kcoap.crypto.testsuite.ScriptedRandom
import org.bouncycastle.crypto.params.MLDSAParameters
import org.bouncycastle.crypto.params.MLDSAPrivateKeyParameters
import org.bouncycastle.crypto.params.MLKEMParameters
import org.bouncycastle.crypto.params.MLKEMPrivateKeyParameters
import kotlin.test.assertEquals

// Short names for the vector-heavy tests in this module.
internal fun String.hex(): ByteArray = hexToBytes()

internal fun ByteArray.hex(): String = toHex()

/** Compares as lower-case hex without whitespace, so a failure shows a readable diff against a vector. */
internal fun assertHexEquals(expected: String, actual: ByteArray, message: String) {
    assertEquals(expected.hex().hex(), actual.hex(), message)
}

/** A copy with bit 0 of byte [index] flipped. */
internal fun ByteArray.flipped(index: Int): ByteArray = copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }

/** The raw material of a key made by a software backend. */
internal val PrivateKey.encoded: ByteArray get() = (this as SoftwarePrivateKey).encoded()

/** The expanded decapsulation key `dk` (FIPS 203 Algorithm 16) of an ML-KEM [seed], as Bouncy Castle derives it. */
internal fun expandedKemKey(type: KeyType, seed: ByteArray): ByteArray {
    val parameters = when (type) {
        KeyType.ML_KEM_512 -> MLKEMParameters.ml_kem_512
        KeyType.ML_KEM_768 -> MLKEMParameters.ml_kem_768
        else -> MLKEMParameters.ml_kem_1024.also { check(type == KeyType.ML_KEM_1024) { "$type is not ML-KEM" } }
    }
    return MLKEMPrivateKeyParameters(parameters, seed.copyOf())
        .getParametersWithFormat(MLKEMPrivateKeyParameters.EXPANDED_KEY)
        .encoded
}

/** The expanded private key `sk` (FIPS 204 Algorithm 24) of an ML-DSA [seed], as Bouncy Castle derives it. */
internal fun expandedDsaKey(type: KeyType, seed: ByteArray): ByteArray {
    val parameters = when (type) {
        KeyType.ML_DSA_44 -> MLDSAParameters.ml_dsa_44
        KeyType.ML_DSA_65 -> MLDSAParameters.ml_dsa_65
        else -> MLDSAParameters.ml_dsa_87.also { check(type == KeyType.ML_DSA_87) { "$type is not ML-DSA" } }
    }
    return MLDSAPrivateKeyParameters(parameters, seed.copyOf())
        .getParametersWithFormat(MLDSAPrivateKeyParameters.EXPANDED_KEY)
        .encoded
}

/**
 * The default backend with scripted randomness: [randomBytes] hands out [random] in order, so ML-KEM and ML-DSA
 * consume NIST's `d || z`, `m`, `ξ` and `rnd`. Running out is a test failure, and [remaining] shows whether an
 * operation drew exactly what FIPS 203/204 prescribe.
 */
internal class ScriptedRandomBackend(private val random: ScriptedRandom) : CryptoBackend by DefaultCryptoBackend() {
    constructor(vararg script: ByteArray) : this(ScriptedRandom(*script))

    val remaining: Int get() = random.remaining

    override suspend fun randomBytes(size: Int): ByteArray = random.next(size)
}

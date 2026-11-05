package dev.kcoap.crypto

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The codec against the JDK, which signs ECDSA in DER (`SHA256withECDSA`) and in raw `r || s`
 * (`SHA256withECDSAinP1363Format`, IEEE P1363): each form converted must verify as the other.
 */
class EcdsaSignatureCodecJcaTest {
    private val random = Random(8422)

    @Test
    fun derAndRawSignaturesOfTheJdkConvertBothWays() {
        for ((algorithm, curve, hash) in listOf(
            Triple(SignatureAlgorithm.ES256, "secp256r1", "SHA256"),
            Triple(SignatureAlgorithm.ES384, "secp384r1", "SHA384"),
        )) {
            val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
            fun signer(format: String) = Signature.getInstance(hash + "withECDSA" + format).apply { initSign(keys.private) }
            fun verifier(format: String) = Signature.getInstance(hash + "withECDSA" + format).apply { initVerify(keys.public) }
            // Enough signatures that some r or s have a top bit set or start with a zero byte.
            repeat(64) {
                val data = random.nextBytes(random.nextInt(0, 100))

                val der = signer("").apply { update(data) }.sign()
                val raw = assertNotNull(EcdsaSignatureCodec.fromDer(algorithm, der), "the JDK's DER signature decodes")
                assertTrue(verifier("inP1363Format").apply { update(data) }.verify(raw), "$algorithm DER → raw")

                val p1363 = signer("inP1363Format").apply { update(data) }.sign()
                val converted = EcdsaSignatureCodec.toDer(algorithm, p1363)
                assertTrue(verifier("").apply { update(data) }.verify(converted), "$algorithm raw → DER")
            }
        }
    }
}

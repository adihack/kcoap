package dev.kcoap.cose

import dev.kcoap.cbor.CborException
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborValue
import dev.kcoap.cbor.toCbor
import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class CoseTest {
    /** RFC 8613 Appendix C.4: the AAD is the Enc_structure over the aad_array. */
    @Test
    fun encStructureMatchesOscoreAad() {
        val aadArray = "8501810a40411440".hexToBytes()
        assertEquals(
            "8368456e63727970743040488501810a40411440",
            CoseStructures.encStructure(protected = ByteArray(0), externalAad = aadArray).toHex(),
        )
    }

    @Test
    fun sigStructureLayout() {
        val encoded = CoseStructures.sigStructure("a10127".hexToBytes(), "aa".hexToBytes(), "bbcc".hexToBytes())
        // ["Signature1", h'a10127', h'aa', h'bbcc']
        assertEquals("846a5369676e61747572653143a1012741aa42bbcc", encoded.toHex())
    }

    @Test
    fun ec2KeyRoundTripsAndExportsSec1() {
        val key = CoseKey(CoseKeyType.EC2, CoseCurve.P_256, x = ByteArray(32) { 1 }, y = ByteArray(32) { 2 }, kid = byteArrayOf(0x32))
        val decoded = CoseKey.fromCbor(key.toCbor())
        assertEquals(key, decoded)
        assertEquals(65, decoded.rawPublicKey().size)
        assertEquals(0x04, decoded.rawPublicKey()[0].toInt())
        assertContentEquals(byteArrayOf(0x32), decoded.kid)
        assertEquals("CoseKey(EC2, P_256, kid=32)", key.toString())
        // Map order kty, kid, crv, x, y as in RFC 9529.
        assertEquals(listOf(1L, 2L, -1L, -2L, -3L), key.toCbor().entries.map { (it.first as dev.kcoap.cbor.CborInt).value })
    }

    @Test
    fun okpAndAkpKeys() {
        val okp = CoseKey(CoseKeyType.OKP, CoseCurve.ED25519, x = ByteArray(32) { 7 })
        assertContentEquals(ByteArray(32) { 7 }, okp.rawPublicKey())
        assertNull(okp.y)
        assertNull(okp.kid)
        val akp = CoseKey(CoseKeyType.AKP, curve = null, x = ByteArray(1312), algorithm = -48)
        assertEquals(akp, CoseKey.fromCbor(akp.toCbor()))
        assertEquals(-48, CoseKey.fromCbor(akp.toCbor()).algorithm)
        // draft-ietf-cose-dilithium: { 1: 7 (AKP), 3: -48 (ML-DSA-44), -1: pub }
        assertEquals(listOf(1L, 3L, -1L), akp.toCbor().entries.map { (it.first as dev.kcoap.cbor.CborInt).value })
        assertFailsWith<CborException> { CoseKey.fromCbor(CborMap(1.toCbor() to 7.toCbor(), 3.toCbor() to (-48).toCbor())) }
        assertFailsWith<CborException> { CoseKey.fromCbor(CborMap(1.toCbor() to 7.toCbor(), (-1).toCbor() to ByteArray(4).toCbor())) }
        assertNotEquals(okp, akp)
    }

    @Test
    fun invalidKeys() {
        assertFailsWith<IllegalArgumentException> { CoseKey(CoseKeyType.EC2, CoseCurve.P_256, ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { CoseKey(CoseKeyType.OKP, CoseCurve.X25519, ByteArray(32), ByteArray(32)) }
        assertFailsWith<IllegalArgumentException> { CoseKey(CoseKeyType.AKP, CoseCurve.X25519, ByteArray(32), algorithm = -48) }
        assertFailsWith<CborException> { CoseKey.fromCbor(CborMap(1.toCbor() to 99.toCbor())) }
        assertFailsWith<CborException> { CoseKey.fromCbor(CborMap(1.toCbor() to 1.toCbor(), (-1).toCbor() to 99.toCbor())) }
        assertFailsWith<CborException> { CoseKey.fromCbor(CborMap(1.toCbor() to 1.toCbor(), (-1).toCbor() to 4.toCbor())) }
        assertFailsWith<CborException> {
            CoseKey.fromCbor(
                CborMap(1.toCbor() to 2.toCbor(), (-1).toCbor() to 1.toCbor(), (-2).toCbor() to ByteArray(32).toCbor(), (-3).toCbor() to "y".toCbor()),
            )
        }
        assertEquals(CoseKeyType.EC2, CoseKeyType.fromValue(2))
        assertEquals(CoseCurve.X25519, CoseCurve.fromValue(4))
    }

    /**
     * RFC 9053 §7.1.1: y as a sign bit, `true` for an odd y. The RFC 9529 §3.4 key PK_R has an even y, so
     * `false` gives it back, and `true` the other point with the same x.
     */
    @Test
    fun rfc9053Section7_1_1SignBitsExpandToTheCoordinate() {
        val x = "bbc34960526ea4d32e940cad2a234148ddc21791a12afbcbac93622046dd44f0"
        val y = "4519e257236b2a0ce2023f0931f1f386ca7afda64fcde0108c224c51eabf6072"
        fun key(curve: Int, x: String, y: CborValue, kty: Int = 2) = CoseKey.fromCbor(
            CborMap(1.toCbor() to kty.toCbor(), (-1).toCbor() to curve.toCbor(), (-2).toCbor() to x.hexToBytes().toCbor(), (-3).toCbor() to y),
        )
        val even = key(1, x, false.toCbor())
        assertEquals(y, even.y!!.toHex())
        assertEquals("04$x$y", even.rawPublicKey().toHex())
        assertEquals(key(1, x, y.hexToBytes().toCbor()), even, "the same key as with the coordinate")
        val odd = key(1, x, true.toCbor())
        assertEquals(1, odd.y!!.last().toInt() and 1)
        // P-384: the base point of SEC 2 v2 §2.5.1, whose y is odd.
        val gx = "aa87ca22be8b05378eb1c71ef320ad746e1d3b628ba79b9859f741e082542a385502f25dbf55296c3a545e3872760ab7"
        val gy = "3617de4a96262c6f5d9e98bf9292dc29f8f41dbd289a147ce9da3113b5f0b8c00a60b1ce1d7e819d7a431d7c90ea0e5f"
        assertEquals(gy, key(2, gx, true.toCbor()).y!!.toHex())

        val notOnTheCurve = "00".repeat(31) + "01"
        assertFailsWith<CborException> { key(1, notOnTheCurve, true.toCbor()) }
        assertFailsWith<CborException> { key(1, "00".repeat(31), true.toCbor()) }
        assertFailsWith<CborException> { key(4, x, true.toCbor()) }
        assertFailsWith<CborException> { key(4, x, true.toCbor(), kty = 1) }
    }

    /** RFC 9529 §3: CRED_R of trace 2, a CCS for the subject "example.edu" with a P-256 key. */
    @Test
    fun cwtClaimsSetKeepsReceivedBytes() {
        val credR = (
            "a2026b6578616d706c652e65647508a101a501020241322001215820bbc34960526ea4d32e940cad2a23414" +
                "8ddc21791a12afbcbac93622046dd44f02258204519e257236b2a0ce2023f0931f1f386ca7afda64fcde0108c224c51eabf6072"
            ).hexToBytes()
        val ccs = CwtClaimsSet.decode(credR)
        assertEquals("example.edu", ccs.subject)
        assertContentEquals(byteArrayOf(0x32), ccs.confirmationKey.kid)
        assertEquals(CoseCurve.P_256, ccs.confirmationKey.curve)
        assertContentEquals(credR, ccs.encoded)
        assertContentEquals(credR, CwtClaimsSet(ccs.subject, ccs.confirmationKey).encoded)
        assertEquals("CwtClaimsSet(subject=example.edu, CoseKey(EC2, P_256, kid=32))", ccs.toString())
        assertFailsWith<CborException> { CwtClaimsSet.decode("a0".hexToBytes()) }
        assertFailsWith<CborException> { CwtClaimsSet.decode("a108a0".hexToBytes()) }
    }
}

package dev.kcoap.edhoc

import dev.kcoap.cbor.CborArray
import dev.kcoap.cbor.CborBool
import dev.kcoap.cbor.CborBytes
import dev.kcoap.cbor.CborInt
import dev.kcoap.cbor.CborMap
import dev.kcoap.cbor.CborText
import dev.kcoap.cbor.toCbor
import dev.kcoap.common.toHex
import dev.kcoap.cose.CoseCurve
import dev.kcoap.cose.CoseKey
import dev.kcoap.cose.CoseKeyType
import dev.kcoap.cose.CwtClaimsSet
import dev.kcoap.crypto.backend.DefaultCryptoBackend
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wire encodings of RFC 9528: identifiers, ID_CRED_x, EAD, error messages and message framing. */
class EncodingTest {
    /** RFC 9528 §3.3.2: the examples, plus the empty byte string. */
    @Test
    fun connectionIdentifierRepresentation() {
        val examples = mapOf(
            "21" to "21", "0d" to "0d", "18" to "4118", "38" to "4138", "abcd" to "42abcd", "" to "40", "37" to "37",
        )
        for ((identifier, encoding) in examples) {
            assertEquals(encoding, cid(identifier).encode().toHex(), identifier)
            val decoded = ConnectionIdentifier.decode(dev.kcoap.cbor.Cbor.decode(encoding.hex()), "test")
            assertEquals(cid(identifier), decoded)
        }
        assertEquals("37", ConnectionIdentifier.ofInt(-24).bytes.toHex())
        assertEquals("17", ConnectionIdentifier.ofInt(23).bytes.toHex())
        assertFailsWith<IllegalArgumentException> { ConnectionIdentifier.ofInt(24) }
        assertFailsWith<IllegalArgumentException> { ConnectionIdentifier.ofInt(-25) }
        assertEquals(2, cid("abcd").size)
        assertEquals("ConnectionIdentifier(abcd)", cid("abcd").toString())
        assertEquals(cid("abcd").hashCode(), cid("abcd").hashCode())
        assertNotEquals<Any>(cid("abcd"), "abcd")
        for (invalid in listOf(CborInt(24), CborInt(-25), CborBytes(byteArrayOf(0x0e)), CborText("x"))) {
            assertFailsWith<EdhocException>(invalid.toString()) { ConnectionIdentifier.decode(invalid, "test") }
        }
    }

    /** RFC 9528 §3.5.3.2: a lone 'kid' is sent as its byte string identifier representation. */
    @Test
    fun compactKidEncoding() {
        assertEquals("a10441ff", CredentialIdentifier.KeyId("ff".hex()).encoded.toHex())
        assertEquals("41ff", CredentialIdentifier.KeyId("ff".hex()).encodeForPlaintext().toHex())
        assertEquals("21", CredentialIdentifier.KeyId("21".hex()).encodeForPlaintext().toHex())
        assertEquals(CredentialIdentifier.KeyId("21".hex()), plaintextIdentifier("21"))
        assertEquals(CredentialIdentifier.KeyId("ff".hex()), plaintextIdentifier("41ff"))
        val kid = CredentialIdentifier.KeyId("ff".hex())
        assertContentEquals("ff".hex(), kid.kid)
        assertFalse(kid.isByValue)
        assertEquals("KeyId(ff)", kid.toString())
        assertEquals(CborMap(CborInt(4) to CborBytes("ff".hex())), kid.header)
        assertFailsWith<EdhocException> { CredentialIdentifier.decodeFromPlaintext("a10441ff".hex(), "test") }
        assertFailsWith<EdhocException> { CredentialIdentifier.decodeFromPlaintext("6178".hex(), "test") }
    }

    @Test
    fun credentialIdentifierTypes() = runTest {
        val thumbprint = CredentialIdentifier.X509Thumbprint(-15, "0102030405060708".hex())
        assertEquals("a11822822e480102030405060708", thumbprint.encoded.toHex())
        assertEquals(thumbprint, CredentialIdentifier.decodeFromPlaintext(thumbprint.encoded, "test"))
        assertEquals(-15, thumbprint.algorithm)
        assertContentEquals("0102030405060708".hex(), thumbprint.hash)
        assertFalse(thumbprint.isByValue)
        assertEquals("X509Thumbprint(-15, 0102030405060708)", thumbprint.toString())

        val one = CredentialIdentifier.X509Chain(listOf("01".hex()))
        assertEquals("a1182141" + "01", one.encoded.toHex())
        val two = CredentialIdentifier.X509Chain(listOf("01".hex(), "0203".hex()))
        assertEquals("a1182182410142" + "0203", two.encoded.toHex())
        assertEquals(two, CredentialIdentifier.decodeFromPlaintext(two.encoded, "test"))
        assertEquals(one, CredentialIdentifier.decodeFromPlaintext(one.encoded, "test"))
        assertTrue(two.isByValue)
        assertEquals(2, two.certificates.size)
        assertEquals("X509Chain(2 certificates)", two.toString())
        assertFailsWith<IllegalArgumentException> { CredentialIdentifier.X509Chain(emptyList()) }

        val key = CoseKey(CoseKeyType.OKP, CoseCurve.X25519, ByteArray(32) { 1 }, kid = "05".hex())
        val claims = CwtClaimsSet("me", key)
        val kccs = CredentialIdentifier.Kccs(claims)
        assertEquals("a10e" + claims.encoded.toHex(), kccs.encoded.toHex())
        val decoded = CredentialIdentifier.decodeFromPlaintext(kccs.encoded, "test")
        assertIs<CredentialIdentifier.Kccs>(decoded)
        assertContentEquals(claims.encoded, decoded.claimsSet.encoded)
        assertContentEquals("05".hex(), decoded.credential().kid)
        assertTrue(kccs.isByValue)
        assertTrue(kccs.toString().startsWith("Kccs("))

        val several = CborMap(
            CborInt(4) to CborBytes("01".hex()),
            CborInt(34) to CborArray(CborInt(-15), CborBytes(ByteArray(8))),
        )
        val other = CredentialIdentifier.decodeFromPlaintext(several.encode(), "test")
        assertIs<CredentialIdentifier.Other>(other)
        assertEquals(several, other.header)
        assertFalse(other.isByValue)
        assertEquals(CredentialIdentifier.Other(several), other)
        assertTrue(other.toString().startsWith("CredentialIdentifier({4: "))
        val unknownLabel = CborMap(CborInt(35) to CborText("coap://example/cert")).encode()
        assertIs<CredentialIdentifier.Other>(CredentialIdentifier.decodeFromPlaintext(unknownLabel, "test"))
        assertEquals(other.hashCode(), CredentialIdentifier.Other(several).hashCode())
    }

    @Test
    fun malformedCredentialIdentifiers() {
        val invalid = listOf(
            CborMap(CborInt(34) to CborBytes(ByteArray(8))),
            CborMap(CborInt(34) to CborArray(CborText("sha"), CborBytes(ByteArray(8)))),
            CborMap(CborInt(34) to CborArray(CborInt(-15))),
            CborMap(CborInt(34) to CborArray(CborInt(1L shl 40), CborBytes(ByteArray(8)))),
            CborMap(CborInt(33) to CborArray(CborBytes(ByteArray(1)))),
            CborMap(CborInt(33) to CborArray(CborBytes(ByteArray(1)), CborInt(1))),
            CborMap(CborInt(33) to CborInt(1)),
            CborMap(CborInt(14) to CborInt(1)),
            CborMap(CborInt(14) to CborMap(CborInt(2) to CborText("no cnf"))),
        )
        for (map in invalid) {
            assertFailsWith<EdhocException>(map.toString()) { plaintextIdentifier(map.encode().toHex()) }
        }
        assertIs<CredentialIdentifier.Other>(
            CredentialIdentifier.decodeFromPlaintext(CborMap(CborText("x") to CborInt(1)).encode(), "test"),
        )
    }

    @Test
    fun thumbprintAlgorithms() = runTest {
        val backend = DefaultCryptoBackend()
        val der = "3000".hex()
        for (algorithm in listOf(-15, -16, -43, -45)) {
            val thumbprint = CredentialIdentifier.x509Thumbprint(backend, der, algorithm)
            assertTrue(thumbprint.matches(backend, der), "$algorithm")
            assertFalse(thumbprint.matches(backend, "3001".hex()))
        }
        assertEquals(8, CredentialIdentifier.x509Thumbprint(backend, der).hash.size)
        assertFailsWith<IllegalArgumentException> { CredentialIdentifier.x509Thumbprint(backend, der, -44) }
        assertFalse(CredentialIdentifier.X509Thumbprint(-44, ByteArray(64)).matches(backend, der))
    }

    @Test
    fun eadItems() {
        val item = EadItem(-5, byteArrayOf(1, 2))
        assertTrue(item.isCritical)
        assertFalse(item.isPadding)
        assertContentEquals(byteArrayOf(1, 2), item.value)
        assertEquals("EadItem(-5, 2 bytes)", item.toString())
        assertEquals("EadItem(0)", EadItem.padding().toString())
        assertTrue(EadItem.padding().isPadding)
        assertFalse(EadItem.padding().isCritical)
        assertNull(EadItem(3).value)
        assertEquals(item, EadItem(-5, byteArrayOf(1, 2)))
        assertEquals(item.hashCode(), EadItem(-5, byteArrayOf(1, 2)).hashCode())
        assertEquals(EadItem(3).hashCode(), EadItem(3).hashCode())
        assertNotEquals(item, EadItem(-5, byteArrayOf(1)))
        assertNotEquals<Any>(item, "item")
        // RFC 9528 §3.8.1 padding examples.
        assertEquals("00", EadItem.encode(listOf(EadItem.padding())).toHex())
        assertEquals("0040", EadItem.encode(listOf(EadItem.padding(ByteArray(0)))).toHex())
        assertEquals("0041e9", EadItem.encode(listOf(EadItem.padding("e9".hex()))).toHex())
        assertEquals(
            listOf(EadItem(0), EadItem(-5, byteArrayOf(1, 2)), EadItem(7)),
            EadItem.readAll(MessageReader("0024420102".hex() + "07".hex(), "EAD")),
        )
        assertFailsWith<EdhocException> { EadItem.readAll(MessageReader("4101".hex(), "EAD")) }
        assertEquals("none", emptyList<EadItem>().describe())
        assertEquals("[-5, 0]", listOf(item, EadItem.padding()).describe())
    }

    @Test
    fun errorMessages() {
        val unspecified = EdhocError.unspecified("Method not supported")
        assertEquals("0174" + "Method not supported".encodeToByteArray().toHex(), unspecified.encode().toHex())
        assertEquals("Method not supported", EdhocError.decode(unspecified.encode()).diagnostic)
        assertNull(unspecified.cipherSuites)

        assertEquals("0202", EdhocError.wrongSelectedCipherSuite(listOf(2)).encode().toHex())
        assertEquals("02820918", EdhocError.wrongSelectedCipherSuite(listOf(9, 24)).encode().toHex().take(8))
        val twoSuites = EdhocError.wrongSelectedCipherSuite(listOf(9, 24)).encode()
        assertEquals(listOf(9, 24), EdhocError.decode(twoSuites).cipherSuites)
        assertNull(EdhocError.wrongSelectedCipherSuite(listOf(2)).diagnostic)
        assertFailsWith<IllegalArgumentException> { EdhocError.wrongSelectedCipherSuite(emptyList()) }
        assertNull(EdhocError(2, CborArray(CborInt(1))).cipherSuites, "a one-element array is not `suites`")
        assertNull(EdhocError(2, CborArray(CborInt(1), CborText("x"))).cipherSuites)
        assertNull(EdhocError(2, CborText("x")).cipherSuites)
        assertNull(EdhocError(2, CborInt(1L shl 40)).cipherSuites)
        assertNull(EdhocError(1, CborInt(5)).diagnostic)

        assertEquals("03f5", EdhocError.unknownCredentialReferenced().encode().toHex())
        assertEquals(EdhocError(3, CborBool(true)), EdhocError.unknownCredentialReferenced())
        assertEquals(EdhocError(3, CborBool(true)).hashCode(), EdhocError.unknownCredentialReferenced().hashCode())
        assertNotEquals<Any>(EdhocError(3, CborBool(true)), "error")
        assertEquals("EdhocError(3, true)", EdhocError.unknownCredentialReferenced().toString())
        for (malformed in listOf("", "f5f5", "02", "0202" + "00", "1a0000000100")) {
            assertFailsWith<EdhocException>(malformed) { EdhocError.decode(malformed.hex()) }
        }

        val exception = EdhocException(unspecified)
        assertEquals(1, exception.errorCode)
        assertEquals("Method not supported", exception.message)
        assertEquals("EdhocError(2, 2)", EdhocException(EdhocError.wrongSelectedCipherSuite(listOf(2))).message)
        assertContentEquals(unspecified.encode(), exception.errorMessage)
        val peer = EdhocPeerErrorException(unspecified)
        assertNull(peer.errorMessage)
        assertTrue(peer.message!!.startsWith("The peer sent"))
    }

    @Test
    fun messageFraming() {
        val message1 = Message1(3, listOf(6, 2), ByteArray(32), ConnectionIdentifier.ofInt(-24), listOf(EadItem(1)))
        val decoded = Message1.decode(message1.encode())
        assertEquals(listOf(6, 2), decoded.suites)
        assertEquals(2, decoded.selectedSuite)
        assertEquals(listOf(EadItem(1)), decoded.ead)
        assertEquals(ConnectionIdentifier.ofInt(-24), decoded.connectionIdentifier)
        assertFailsWith<EdhocException> { Message1.decode("03025820".hex() + ByteArray(32)) }

        assertEquals("4101", SingleByteStringMessage.encode(byteArrayOf(1)).toHex())
        assertContentEquals(byteArrayOf(1), SingleByteStringMessage.decode("4101".hex(), "m"))
        for (invalid in listOf("", "01", "410100", "6101")) {
            assertFailsWith<EdhocException>(invalid) { SingleByteStringMessage.decode(invalid.hex(), "m") }
        }
        assertEquals(emptyList(), Plaintext4.decode(ByteArray(0)))
        val ead4 = listOf(EadItem(-1, ByteArray(0)))
        assertEquals(ead4, Plaintext4.decode(Plaintext4.encode(ead4)))

        val plaintext3 = AuthenticatedPlaintext(null, CredentialIdentifier.KeyId("2b".hex()), ByteArray(8), emptyList())
        assertEquals("2b48" + "00".repeat(8), plaintext3.encode().toHex())
    }

    private fun plaintextIdentifier(hex: String): CredentialIdentifier =
        CredentialIdentifier.decodeFromPlaintext(hex.hex(), "test")

    @Test
    fun messageReaderRules() {
        val reader = MessageReader("1a00010000".hex() + "1b0000000100000000".hex() + "01".hex(), "test")
        assertEquals(65536, reader.readInt())
        assertFailsWith<EdhocException> { reader.readInt() }
        assertFailsWith<EdhocException> { MessageReader("1800".hex(), "test").readInt() }
        assertFailsWith<EdhocException> { MessageReader("01".hex(), "test").readBytes() }
        val end = MessageReader(ByteArray(0), "test")
        assertNull(end.peekType())
        end.expectEnd()
        assertFalse(MessageReader.startsWithInteger(ByteArray(0)))
        assertTrue(MessageReader.startsWithInteger("20".hex()))
        assertFalse(MessageReader.startsWithInteger("40".hex()))
        assertEquals(dev.kcoap.cbor.CborType.MAP, MessageReader("a0".hex(), "test").peekType())
        assertEquals("a0", MessageReader("a0".hex(), "test").readValue().encode().toHex())
        assertEquals(1, MessageReader(CborArray(1.toCbor()).encode(), "t").readValue().let { (it as CborArray).size })
    }
}

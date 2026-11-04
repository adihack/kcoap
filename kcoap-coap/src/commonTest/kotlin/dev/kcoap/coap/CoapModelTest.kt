package dev.kcoap.coap

import dev.kcoap.common.hexToBytes
import dev.kcoap.transport.CoapScheme
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoapModelTest {
    @Test
    fun codeClassesAndNames() {
        assertEquals("0.01 GET", CoapCode.GET.toString())
        assertEquals("2.05 Content", CoapCode.CONTENT.toString())
        assertEquals("2.17", CoapCode.of(2, 17).toString())
        assertEquals("7.01 CSM", CoapCode.CSM.toString())
        assertTrue(CoapCode.GET.isRequest && !CoapCode.GET.isResponse)
        assertTrue(CoapCode.CONTENT.isSuccess && CoapCode.CONTENT.isResponse)
        assertTrue(CoapCode.NOT_FOUND.isClientError && CoapCode.BAD_GATEWAY.isServerError)
        assertTrue(CoapCode.PING.isSignaling && CoapCode.EMPTY.isEmpty)
        assertFalse(CoapCode.EMPTY.isRequest)
        assertEquals(4, CoapCode.NOT_FOUND.codeClass)
        assertEquals(4, CoapCode.NOT_FOUND.detail)
        assertFailsWith<IllegalArgumentException> { CoapCode(256) }
        assertFailsWith<IllegalArgumentException> { CoapCode.of(8, 0) }
        assertEquals(CoapMessageType.RST, CoapMessageType.fromValue(3))
    }

    @Test
    fun optionsAreSortedAndTyped() {
        val options = OptionSet.build {
            uriQuery("a=1")
            uriPath("/sensors/temp")
            uriHost("example.com")
            uriPort(5684)
            contentFormat(ContentFormat.CBOR)
            accept(ContentFormat.JSON)
            maxAge(30)
            etag(byteArrayOf(1))
            ifMatch(byteArrayOf(2))
            ifNoneMatch()
            observe(0)
            block1(BlockOption(2, more = true, sizeExponent = 2))
            block2(BlockOption(0, more = false, sizeExponent = 6))
            size1(1000)
            size2(2000)
            oscore("0914".hexToBytes())
            edhoc()
            echo(byteArrayOf(9))
            requestTag(byteArrayOf(7))
            hopLimit(16)
            noResponse(26)
            locationPath("created")
            locationQuery("id=1")
            proxyScheme("coap")
            uriQuery("b=2")
        }
        assertEquals(options.map { it.number }.sorted(), options.map { it.number })
        assertEquals(listOf("sensors", "temp"), options.uriPath)
        assertEquals("/sensors/temp", options.path)
        assertEquals(listOf("a=1", "b=2"), options.uriQuery)
        assertEquals("example.com", options.uriHost)
        assertEquals(5684, options.uriPort)
        assertEquals(ContentFormat.CBOR, options.contentFormat)
        assertEquals(ContentFormat.JSON, options.accept)
        assertEquals(30, options.maxAge)
        assertContentEquals(byteArrayOf(1), options.etags.single())
        assertContentEquals(byteArrayOf(2), options.ifMatch.single())
        assertTrue(options.ifNoneMatch)
        assertEquals(0, options.observe)
        assertEquals(BlockOption(2, true, 2), options.block1)
        assertEquals(1024, options.block2?.size)
        assertEquals(1000, options.size1)
        assertEquals(2000, options.size2)
        assertContentEquals("0914".hexToBytes(), options.oscore)
        assertTrue(options.edhoc)
        assertContentEquals(byteArrayOf(9), options.echo)
        assertContentEquals(byteArrayOf(7), options.requestTags.single())
        assertEquals(16, options.hopLimit)
        assertEquals(26, options.noResponse)
        assertEquals(listOf("created"), options.locationPath)
        assertEquals(listOf("id=1"), options.locationQuery)
        assertEquals("coap", options.proxyScheme)
        assertNull(options.proxyUri)
        assertEquals("/", OptionSet.EMPTY.path)
        assertEquals(60, OptionSet.EMPTY.maxAge, "RFC 7252 §5.10.5 default")
    }

    @Test
    fun builderSettersReplaceAndRemove() {
        val options = OptionSet.build {
            contentFormat(0)
            contentFormat(50)
            uriPath("a/b")
            uriPath("c")
            proxyUri("coap://example.com/x")
            add(65000, byteArrayOf(1))
            remove(65000)
        }
        assertEquals(50, options.contentFormat)
        assertEquals(listOf("c"), options.uriPath)
        assertEquals("coap://example.com/x", options.proxyUri)
        assertFalse(65000 in options)
        val copy = options.toBuilder().clear().build()
        assertTrue(copy.isEmpty())
        assertEquals(OptionSet.of(options), options)
        assertEquals(options.hashCode(), OptionSet.of(options).hashCode())
    }

    @Test
    fun optionRendering() {
        assertEquals("Uri-Path: tv1", CoapOption.string(11, "tv1").toString())
        assertEquals("Observe: 0", CoapOption.uint(6, 0).toString())
        assertEquals("If-None-Match", CoapOption.empty(5).toString())
        assertEquals("OSCORE: 0914", CoapOption(9, "0914".hexToBytes()).toString())
        assertEquals("65000: 0a", CoapOption(65000, byteArrayOf(10)).toString())
        assertEquals(1, CoapOption(65000, byteArrayOf(10)).length)
        assertFailsWith<IllegalArgumentException> { CoapOption(65536, ByteArray(0)) }
        assertFailsWith<IllegalStateException> { CoapOption(14, ByteArray(8)).uintValue() }
    }

    @Test
    fun blockOptionEncoding() {
        val block = BlockOption(number = 3, more = true, sizeExponent = 2)
        assertEquals(64, block.size)
        assertEquals(192, block.offset)
        assertEquals(0x3A, block.toUint())
        assertEquals(block, BlockOption.fromUint(0x3A))
        assertEquals("3/1/64", block.toString())
        assertTrue(BlockOption(0, false, 7).isBert)
        assertEquals(1024, BlockOption(0, false, 7).size)
        assertEquals(6, BlockOption.sizeExponentFor(1024))
        assertEquals(0, BlockOption.sizeExponentFor(16))
        assertFailsWith<IllegalArgumentException> { BlockOption.sizeExponentFor(100) }
        assertFailsWith<IllegalArgumentException> { BlockOption(BlockOption.MAX_NUMBER + 1, false, 0) }
        assertFailsWith<IllegalArgumentException> { BlockOption(0, false, 8) }
        assertFailsWith<IllegalArgumentException> { BlockOption.fromUint(0x1000000) }
        assertEquals(block.hashCode(), BlockOption.fromUint(0x3A).hashCode())
    }

    @Test
    fun messagesValidateAndRender() {
        val request = CoapMessage.request(CoapCode.GET) {
            messageId = 23
            token = "4a7b".hexToBytes()
            options { uriPath("tv1") }
            payload("hello")
        }
        assertEquals("CON 0.01 GET mid=23 token=4a7b [Uri-Path: tv1] 5 bytes", request.toString())
        assertTrue(request.isRequest && !request.isResponse && !request.isSignaling && !request.isEmpty)
        assertTrue(request.hasToken("4a7b".hexToBytes()))
        assertEquals("hello", request.payloadAsText())

        val response = CoapMessage.response(request, CoapCode.CONTENT) { type = CoapMessageType.ACK }
        assertContentEquals(request.token, response.token)
        assertTrue(response.isResponse)
        assertEquals(request, request.toBuilder().build())
        assertEquals(request.hashCode(), request.toBuilder().build().hashCode())

        val replaced = request.toBuilder().replaceOptions(OptionSet.build { uriPath("x") }).build()
        assertEquals(listOf("x"), replaced.options.uriPath)

        assertFailsWith<IllegalArgumentException> { CoapMessage.request(CoapCode.CONTENT) }
        assertFailsWith<IllegalArgumentException> { CoapMessage.response(request, CoapCode.GET) }
        assertFailsWith<IllegalArgumentException> { CoapMessage.build { messageId = 70000 } }
        assertFailsWith<IllegalArgumentException> { CoapMessage.build { code = CoapCode.GET; token = ByteArray(9) } }
        assertFailsWith<IllegalArgumentException> { CoapMessage.build { token = byteArrayOf(1) } }
        assertEquals("ACK 0.00 mid=7", CoapMessage.empty(CoapMessageType.ACK, 7).toString())
    }

    @Test
    fun uriDecomposition() {
        val uri = CoapUri.parse("coap://Example.COM:5683/~sensors/temp%20a?first=1&rt=x%26y")
        assertEquals(CoapScheme.COAP, uri.scheme)
        assertEquals("example.com", uri.host)
        assertEquals(5683, uri.port)
        assertEquals(listOf("~sensors", "temp a"), uri.pathSegments)
        assertEquals(listOf("first=1", "rt=x&y"), uri.querySegments)
        assertEquals("coap://example.com/~sensors/temp%20a?first=1&rt=x%26y", uri.toString())
        val options = uri.toOptions()
        assertEquals("example.com", options.uriHost)
        assertNull(options.uriPort)
        assertEquals(listOf("~sensors", "temp a"), options.uriPath)
        assertEquals(5683, uri.toOptions(destinationPort = 5684).uriPort)
        assertEquals(uri, CoapUri.fromOptions(CoapScheme.COAP, "192.0.2.1", 5683, options))
    }

    @Test
    fun uriLiteralsAndEdgeCases() {
        val v6 = CoapUri.parse("coaps://[2001:DB8::1]:6000/")
        assertEquals("2001:db8::1", v6.host)
        assertTrue(v6.isIpLiteral)
        assertEquals("coaps://[2001:db8::1]:6000/", v6.toString())
        assertNull(v6.toOptions().uriHost)

        val v4 = CoapUri.parse("coap+tcp://192.0.2.1")
        assertTrue(v4.isIpLiteral)
        assertEquals(CoapScheme.COAP_TCP, v4.scheme)
        assertEquals("/", v4.path)
        assertEquals("coap+tcp://192.0.2.1/", v4.toString())

        assertEquals(listOf("a", "", "b"), CoapUri.parse("coap://h/a//b").pathSegments)
        assertEquals(listOf(""), CoapUri.parse("coap://h/?").querySegments)
        assertEquals("coap://h/%C3%BC", CoapUri(CoapScheme.COAP, "h", pathSegments = listOf("ü")).toString())
        assertEquals(CoapUri.parse("coap://h/x").hashCode(), CoapUri.parse("coap://h:5683/x").hashCode())

        assertFailsWith<IllegalArgumentException> { CoapUri.parse("/relative") }
        assertFailsWith<IllegalArgumentException> { CoapUri.parse("http://example.com/") }
        assertFailsWith<IllegalArgumentException> { CoapUri.parse("coap://example.com/#frag") }
        assertFailsWith<IllegalArgumentException> { CoapUri.parse("coap:///path") }
        assertFailsWith<IllegalArgumentException> { CoapUri.parse("coap://h/%zz") }
        assertFailsWith<IllegalArgumentException> { CoapUri.parse("coap://h/%2") }
        assertFailsWith<IllegalArgumentException> { CoapUri(CoapScheme.COAP, "h", port = 0) }
    }
}

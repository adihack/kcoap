package dev.kcoap.coap.client

import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.transport.Endpoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class CoapClientConfigTest {
    private val security = object : ClientMessageSecurity {
        override suspend fun protectRequest(request: CoapMessage, peer: Endpoint): ProtectedRequest? = null
    }
    private val resolver = EndpointResolver { Endpoint.Udp("192.0.2.1", it.port) }
    private val qBlock = QBlockClientConfig(QBlockMode.PROBE)

    /** Sets every option to a value other than its default. */
    private fun CoapClientConfig.Builder.setEverything() {
        security = this@CoapClientConfigTest.security
        requestTimeout = 5.seconds
        preferredBlockSize = 64
        maxResponseBodySize = 4096
        resolver = this@CoapClientConfigTest.resolver
        observeCancellation = ObserveCancellation.FORGET
        echoRetry = false
        securityReserve = 32
        qBlock = this@CoapClientConfigTest.qBlock
    }

    private fun assertEverythingSet(config: CoapClientConfig) {
        assertSame(security, config.security)
        assertEquals(5.seconds, config.requestTimeout)
        assertEquals(64, config.preferredBlockSize)
        assertEquals(4096, config.maxResponseBodySize)
        assertSame(resolver, config.resolver)
        assertEquals(ObserveCancellation.FORGET, config.observeCancellation)
        assertEquals(false, config.echoRetry)
        assertEquals(32, config.securityReserve)
        assertSame(qBlock, config.qBlock)
    }

    @Test
    fun theDslSetsEveryOption() {
        assertEverythingSet(CoapClientConfig { setEverything() })
    }

    @Test
    fun theBuilderSetsEveryOption() {
        val builder = CoapClientConfig.Builder()
        builder.setEverything()
        assertEverythingSet(builder.build())
    }

    @Test
    fun optionsNotSetKeepTheirDefaults() {
        val config = CoapClientConfig { requestTimeout = 2.seconds }
        val defaults = CoapClientConfig.Default
        assertEquals(2.seconds, config.requestTimeout)
        assertNull(config.security)
        assertNull(defaults.requestTimeout, "EXCHANGE_LIFETIME")
        assertNull(defaults.preferredBlockSize)
        assertEquals(1 shl 20, defaults.maxResponseBodySize)
        assertSame(EndpointResolver.Default, defaults.resolver)
        assertEquals(ObserveCancellation.DEREGISTER, defaults.observeCancellation)
        assertTrue(defaults.echoRetry, "RFC 9175 §2.3: SHOULD")
        assertEquals(64, defaults.securityReserve)
        assertEquals(QBlockMode.OFF, defaults.qBlock.mode, "Q-Block is off by default (ADR 0006)")
    }

    @Test
    fun toBuilderStartsFromTheConfiguration() {
        val original = CoapClientConfig { setEverything() }
        assertEverythingSet(original.toBuilder().build())
        val changed = original.toBuilder().apply { echoRetry = true }.build()
        assertTrue(changed.echoRetry)
        assertEquals(false, original.echoRetry, "the original stays as it was")
        assertSame(security, changed.security)
    }

    @Test
    fun aBuiltConfigurationDoesNotFollowItsBuilder() {
        val builder = CoapClientConfig.Builder().apply { preferredBlockSize = 128 }
        val first = builder.build()
        builder.preferredBlockSize = 256
        assertEquals(128, first.preferredBlockSize)
        assertEquals(256, builder.build().preferredBlockSize)
    }

    /** RFC 7959 §2.2: SZX gives block sizes 2^(SZX+4), 16..1024. */
    @Test
    fun rfc7959Section2_2BuildRejectsBlockSizesSzxCannotExpress() {
        for (size in listOf(8, 100, 2048)) {
            val builder = CoapClientConfig.Builder().apply { preferredBlockSize = size }
            val error = assertFailsWith<IllegalArgumentException> { builder.build() }
            assertEquals("Block size must be a power of two in 16..1024: $size", error.message)
        }
        for (size in listOf(16, 32, 1024)) assertEquals(size, CoapClientConfig { preferredBlockSize = size }.preferredBlockSize)
    }

    @Test
    fun buildRejectsOtherValuesOutOfRange() {
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { requestTimeout = (-1).seconds } }
        assertFailsWith<IllegalArgumentException> { CoapClientConfig { maxResponseBodySize = -1 } }
        assertEquals(0, CoapClientConfig { securityReserve = 0 }.securityReserve)
    }
}

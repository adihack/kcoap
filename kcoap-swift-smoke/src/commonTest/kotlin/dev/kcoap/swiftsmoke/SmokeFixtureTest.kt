package dev.kcoap.swiftsmoke

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.coap.client.ObserveCancellation
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.common.KcoapException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/** The Kotlin side of what `src/swift/SwiftSmoke.swift` checks from Swift, on the Apple targets. */
class SmokeFixtureTest {
    private fun explicitConfig(): CoapClientConfig {
        val builder = CoapClientConfig.Builder()
        builder.preferredBlockSize = 256
        builder.maxResponseBodySize = 65_536
        builder.echoRetry = false
        builder.securityReserve = 0
        builder.observeCancellation = ObserveCancellation.FORGET
        SmokeFixture.applyRequestTimeout(builder, 300)
        return builder.build()
    }

    @Test
    fun theBuilderKeepsEveryExplicitValue() {
        val config = explicitConfig()

        assertEquals(256, config.preferredBlockSize)
        assertEquals(65_536, config.maxResponseBodySize)
        assertFalse(config.echoRetry)
        assertEquals(0, config.securityReserve)
        assertEquals(ObserveCancellation.FORGET, config.observeCancellation)
        assertEquals(300.milliseconds, config.requestTimeout)
        assertEquals(300, SmokeFixture.requestTimeoutMilliseconds(config))
        assertEquals(-1, SmokeFixture.requestTimeoutMilliseconds(CoapClientConfig.Default))
    }

    @Test
    fun aRequestNobodyAnswersFailsWithResponseTimeout() = runTest {
        val client = SmokeFixture.unansweredClient(explicitConfig())
        val request = SmokeFixture.getRequest(CoapUri.parse("coap://127.0.0.1:5683/smoke"))
        assertEquals(CoapCode.GET, request.method)
        assertTrue(request.isConfirmable)

        // Real time, as from Swift: the client's own timers run on Dispatchers.Default.
        val failure = withContext(Dispatchers.Default) {
            assertFailsWith<KcoapException> { client.request(request) }
        }
        client.close()

        assertIs<CoapException>(failure)
        assertEquals(CoapException.Reason.RESPONSE_TIMEOUT, failure.reason)
        assertEquals("dev.kcoap.coap.stack.CoapException", SmokeFixture.kotlinClassName(failure))
    }

    @Test
    fun aMalformedUriIsAnIllegalArgument() {
        val failure = assertFailsWith<IllegalArgumentException> { CoapUri.parse("coap://[::1/broken") }

        assertTrue(SmokeFixture.isIllegalArgument(failure))
        assertFalse(SmokeFixture.isIllegalArgument(IllegalStateException("not this")))
        assertFalse(SmokeFixture.isIllegalArgument(null))
        assertEquals("null", SmokeFixture.kotlinClassName(null))
    }
}

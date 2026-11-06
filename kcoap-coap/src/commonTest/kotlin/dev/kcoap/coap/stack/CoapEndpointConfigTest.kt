package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapMessage
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LoggerFactory
import dev.kcoap.transport.Endpoint
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CoapEndpointConfigTest {
    private val parameters = TransmissionParameters(ackTimeout = 3.seconds, maxRetransmit = 6)
    private val tokens = TokenGenerator.sessionSequence(random = Random(1))
    private val clock = TestTimeSource()
    private val random = Random(2)
    private val listener = EndpointEventListener { }
    private val loggers = LoggerFactory(LogConfig.Default)
    private val reliable = ReliableTransportConfig(idleTimeout = null)
    private val tracer = object : MessageInterceptor {
        override fun onSend(to: Endpoint, message: CoapMessage): CoapMessage = message
    }

    /** Sets every option to a value other than its default. */
    private fun CoapEndpointConfig.Builder.setEverything() {
        parameters = this@CoapEndpointConfigTest.parameters
        separateResponseDelay = 500.milliseconds
        tokenGenerator = tokens
        timeSource = clock
        random = this@CoapEndpointConfigTest.random
        interceptors = listOf(tracer)
        eventListener = listener
        loggerFactory = loggers
        reliable = this@CoapEndpointConfigTest.reliable
        maxDeduplicationEntries = 10
        maxConcurrentRequests = 2
        maxExchangesPerPeer = 3
        maxDeduplicationEntriesPerPeer = 4
        maxExchanges = 5
        maxReservedMessageIds = 6
        maxReservedMessageIdsPerPeer = 7
    }

    /** The bounds the deprecated constructor does not take. */
    private fun assertNewerBoundsSet(config: CoapEndpointConfig) {
        assertEquals(4, config.maxDeduplicationEntriesPerPeer)
        assertEquals(5, config.maxExchanges)
        assertEquals(6, config.maxReservedMessageIds)
        assertEquals(7, config.maxReservedMessageIdsPerPeer)
    }

    private fun assertEverythingSet(config: CoapEndpointConfig) {
        assertSame(parameters, config.parameters)
        assertEquals(500.milliseconds, config.separateResponseDelay)
        assertSame(tokens, config.tokenGenerator)
        assertSame(clock, config.timeSource)
        assertSame(random, config.random)
        assertEquals(listOf<MessageInterceptor>(tracer), config.interceptors)
        assertSame(listener, config.eventListener)
        assertSame(loggers, config.loggerFactory)
        assertSame(reliable, config.reliable)
        assertEquals(10, config.maxDeduplicationEntries)
        assertEquals(2, config.maxConcurrentRequests)
        assertEquals(3, config.maxExchangesPerPeer)
    }

    @Test
    fun theDslSetsEveryOption() {
        val config = CoapEndpointConfig { setEverything() }
        assertEverythingSet(config)
        assertNewerBoundsSet(config)
    }

    @Test
    fun theBuilderSetsEveryOption() {
        val builder = CoapEndpointConfig.Builder()
        builder.setEverything()
        assertEverythingSet(builder.build())
        assertNewerBoundsSet(builder.build())
    }

    @Test
    fun optionsNotSetKeepTheirDefaults() {
        val config = CoapEndpointConfig { maxConcurrentRequests = 8 }
        val defaults = CoapEndpointConfig.Default
        assertEquals(8, config.maxConcurrentRequests)
        assertSame(TransmissionParameters.Default, config.parameters)
        assertEquals(1.seconds, defaults.separateResponseDelay)
        assertNull(defaults.tokenGenerator, "every endpoint makes its own generator")
        assertTrue(defaults.interceptors.isEmpty())
        assertSame(EndpointEventListener.None, defaults.eventListener)
        assertSame(LoggerFactory.Global, defaults.loggerFactory)
        assertEquals(CoapEndpointConfig.DEFAULT_MAX_DEDUPLICATION_ENTRIES, config.maxDeduplicationEntries)
        assertEquals(CoapEndpointConfig.DEFAULT_MAX_EXCHANGES_PER_PEER, config.maxExchangesPerPeer)
        assertEquals(CoapEndpointConfig.DEFAULT_MAX_CONCURRENT_REQUESTS, defaults.maxConcurrentRequests)
        assertEquals(10_000, defaults.maxDeduplicationEntriesPerPeer)
        assertEquals(16_384, defaults.maxExchanges)
        assertEquals(262_144, defaults.maxReservedMessageIds)
        assertEquals(65_536, defaults.maxReservedMessageIdsPerPeer, "the whole Message ID space")
    }

    @Test
    fun toBuilderStartsFromTheConfiguration() {
        val original = CoapEndpointConfig { setEverything() }
        assertEverythingSet(original.toBuilder().build())
        assertNewerBoundsSet(original.toBuilder().build())
        val changed = original.toBuilder().apply { maxExchangesPerPeer = 99 }.build()
        assertEquals(99, changed.maxExchangesPerPeer)
        assertEquals(3, original.maxExchangesPerPeer, "the original stays as it was")
        assertSame(clock, changed.timeSource)
    }

    @Test
    fun aBuiltConfigurationDoesNotFollowItsBuilder() {
        val interceptors = mutableListOf<MessageInterceptor>(tracer)
        val builder = CoapEndpointConfig.Builder().apply { this.interceptors = interceptors }
        val first = builder.build()
        interceptors += tracer
        builder.maxConcurrentRequests = 1
        val second = builder.build()
        assertEquals(1, first.interceptors.size, "the list given is copied")
        assertEquals(CoapEndpointConfig.DEFAULT_MAX_CONCURRENT_REQUESTS, first.maxConcurrentRequests)
        assertEquals(1, second.maxConcurrentRequests)
        assertNotSame(first, second)
    }

    @Test
    fun buildRejectsValuesOutOfRange() {
        val negativeDelay = CoapEndpointConfig.Builder().apply { separateResponseDelay = (-1).milliseconds }
        val error = assertFailsWith<IllegalArgumentException> { negativeDelay.build() }
        assertEquals("Separate response delay must not be negative: -1ms", error.message)
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxDeduplicationEntries = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxDeduplicationEntriesPerPeer = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxExchanges = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxReservedMessageIds = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxReservedMessageIdsPerPeer = 0 } }
        assertFailsWith<IllegalArgumentException> { CoapEndpointConfig { maxReservedMessageIdsPerPeer = 65_537 } }
        assertNull(CoapEndpointConfig { separateResponseDelay = null }.separateResponseDelay, "null turns it off")
    }
}

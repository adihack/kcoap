package dev.kcoap.coap

import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.stack.ClockCheck
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.log.LogConfig
import dev.kcoap.log.LogLevel
import dev.kcoap.log.LogRecord
import dev.kcoap.log.LogSink
import dev.kcoap.log.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Timers read the configured time source and wait on the dispatcher's clock, so the two must be one clock
 * (CLR-8). A misconfiguration is reported once instead of silently spinning or stalling the timers.
 */
class TimeSourceContractTest {
    private val records = mutableListOf<LogRecord>()
    private val loggers = LoggerFactory(
        LogConfig.build {
            level = LogLevel.WARN
            sink = LogSink { records += it }
        },
    )

    private val warnings: List<LogRecord> get() = records.filter { "does not advance with its dispatcher's clock" in it.message }

    @Test
    fun aTimeSourceThatIgnoresTheDispatcherClockIsReportedOnce() = runTest {
        val fixture = StackFixture(this)
        // The real clock under runTest: virtual delays end at once while it hardly moves.
        val config = CoapEndpointConfig(timeSource = TimeSource.Monotonic, loggerFactory = loggers)
        val client = CoapClient(CoapEndpoint(fixture.transport(), backgroundScope, config))
        val request = launch { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(60.seconds)

        assertEquals(1, warnings.size)
        assertTrue("testScheduler.timeSource" in warnings.single().message)
        request.cancel()
    }

    @Test
    fun aTimeSourceThatFollowsTheDispatcherClockIsNotReported() = runTest {
        val fixture = StackFixture(this)
        val config = CoapEndpointConfig(timeSource = testScheduler.timeSource, loggerFactory = loggers)
        val client = CoapClient(CoapEndpoint(fixture.transport(), backgroundScope, config))
        val request = launch { runCatching { client.get("coap://127.0.0.1:9/x") } }
        delay(60.seconds)

        assertTrue(warnings.isEmpty())
        request.cancel()
    }

    @Test
    fun theCheckIgnoresShortWaitsAndForgetsAfterAGoodOne() {
        val check = ClockCheck(loggers.logger("test"))
        repeat(5) { check.afterTimeout(5.milliseconds, 0.milliseconds) }
        repeat(2) { check.afterTimeout(2.seconds, 0.milliseconds) }
        check.afterTimeout(2.seconds, 2.seconds)
        repeat(2) { check.afterTimeout(2.seconds, 0.milliseconds) }
        assertTrue(warnings.isEmpty(), "never three bad waits in a row")
        check.afterTimeout(2.seconds, 0.milliseconds)
        check.afterTimeout(2.seconds, 0.milliseconds)
        assertEquals(1, warnings.size)
    }
}

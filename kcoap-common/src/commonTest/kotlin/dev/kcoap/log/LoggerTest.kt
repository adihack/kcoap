package dev.kcoap.log

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class LoggerTest {
    private val epoch = Instant.fromEpochMilliseconds(0)
    private val fixedClock = object : Clock {
        override fun now(): Instant = epoch
    }
    private val records = mutableListOf<LogRecord>()
    private val collect = LogSink { records += it }

    private fun factory(block: LogConfig.Builder.() -> Unit = {}): LoggerFactory =
        LoggerFactory(
            LogConfig.build {
                sink = collect
                clock = fixedClock
                block()
            },
        )

    @AfterTest
    fun resetGlobal() = KcoapLogging.reset()

    @Test
    fun defaultThresholdIsInfo() {
        val log = factory().logger("coap")
        log.debug { "hidden" }
        log.info { "shown" }
        assertEquals(listOf("shown"), records.map { it.message })
        assertEquals(LogLevel.INFO, records.single().level)
        assertEquals(epoch, records.single().timestamp)
    }

    @Test
    fun messageIsNotBuiltWhenDisabled() {
        var built = false
        factory().logger("coap").trace {
            built = true
            "expensive"
        }
        assertFalse(built)
    }

    @Test
    fun subTagsInheritTheClosestLevel() {
        val factory = factory {
            level = LogLevel.WARN
            level("coap", LogLevel.DEBUG)
            level("coap.message", LogLevel.TRACE)
        }
        assertTrue(factory.logger("coap.exchange").isEnabled(LogLevel.DEBUG))
        assertFalse(factory.logger("coap.exchange").isEnabled(LogLevel.TRACE))
        assertTrue(factory.logger("coap.message.udp").isEnabled(LogLevel.TRACE))
        assertFalse(factory.logger("oscore").isEnabled(LogLevel.INFO))
        assertFalse(factory.logger("coapx").isEnabled(LogLevel.DEBUG), "prefix must end at a dot")
    }

    @Test
    fun offDisablesEverything() {
        val log = factory { level = LogLevel.OFF }.logger("edhoc")
        log.error { "nothing" }
        assertTrue(records.isEmpty())
        assertFalse(log.isEnabled(LogLevel.OFF))
    }

    @Test
    fun everyLevelReachesTheSink() {
        val log = factory { level = LogLevel.TRACE }.logger("t")
        val failure = IllegalStateException("boom")
        log.trace { "t" }
        log.debug { "d" }
        log.info { "i" }
        log.warn { "w" }
        log.error(failure) { "e" }
        assertEquals(LogLevel.entries - LogLevel.OFF, records.map { it.level })
        assertSame(failure, records.last().error)
    }

    @Test
    fun withAddsFieldsAndChildExtendsTheTag() {
        val log = factory().logger("coap").with("peer" to "192.0.2.1:5683", "mid" to 7)
        log.child("exchange").info { "Response 2.05" }
        val record = records.single()
        assertEquals("coap.exchange", record.tag)
        assertEquals(mapOf("peer" to "192.0.2.1:5683", "mid" to "7"), record.fields)
        assertEquals(
            "1970-01-01T00:00:00Z I coap.exchange: Response 2.05 {peer=192.0.2.1:5683, mid=7}",
            record.toString(),
        )
    }

    @Test
    fun payloadHexOnlyWhenEnabled() {
        val bytes = byteArrayOf(0x0a, 0xff.toByte())
        assertEquals("2 bytes", factory().logger("t").payload(bytes))
        assertEquals("2 bytes 0aff", factory { payloadDumps = true }.logger("t").payload(bytes))
    }

    @Test
    fun brokenSinkIsIgnored() {
        val log = LoggerFactory(LogConfig.build { sink = LogSink { error("sink down") } }).logger("t")
        log.error { "still fine" }
    }

    @Test
    fun globalConfigurationAppliesToExistingLoggers() {
        val log = KcoapLogging.logger("oscore")
        KcoapLogging.configure {
            sink = collect
            clock = fixedClock
            level = LogLevel.WARN
        }
        log.info { "hidden" }
        KcoapLogging.configure { level(LogTags.OSCORE, LogLevel.DEBUG) }
        log.debug { "shown" }
        assertEquals(listOf("shown"), records.map { it.message })

        KcoapLogging.use(LogConfig.build { sink = collect; level = LogLevel.OFF })
        log.error { "off" }
        assertEquals(1, records.size)
    }

    @Test
    fun clearedLevelInheritsAgain() {
        val config = LogConfig.build {
            level("a", LogLevel.TRACE)
            level("a.b", LogLevel.ERROR)
        }
        assertEquals(LogLevel.ERROR, config.levelFor("a.b.c"))
        assertEquals(LogLevel.TRACE, config.toBuilder().clearLevel("a.b").build().levelFor("a.b.c"))
    }
}

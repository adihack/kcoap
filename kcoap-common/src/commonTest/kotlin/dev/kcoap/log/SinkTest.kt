package dev.kcoap.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SinkTest {
    private fun record(message: String, level: LogLevel = LogLevel.INFO, error: Throwable? = null) =
        LogRecord(Instant.fromEpochMilliseconds(1_000), level, "test", message, error = error)

    @Test
    fun ringBufferKeepsTheNewestInOrder() {
        val sink = RingBufferSink(capacity = 3)
        (1..5).forEach { sink.write(record("m$it")) }
        assertEquals(listOf("m3", "m4", "m5"), sink.snapshot().map { it.message })
        assertEquals(2, sink.dropped)
        assertEquals(
            listOf(
                "1970-01-01T00:00:01Z I test: m3",
                "1970-01-01T00:00:01Z I test: m4",
                "1970-01-01T00:00:01Z I test: m5",
            ),
            sink.dump().lines(),
        )
        sink.clear()
        assertTrue(sink.snapshot().isEmpty())
        assertEquals(0, sink.dropped)
    }

    @Test
    fun ringBufferNeedsCapacity() {
        assertFailsWith<IllegalArgumentException> { RingBufferSink(0) }
    }

    @Test
    fun compositeAndFilter() {
        val all = RingBufferSink(10)
        val warnings = RingBufferSink(10)
        val sink = all + warnings.atLeast(LogLevel.WARN) + LogSink.None
        sink.write(record("info"))
        sink.write(record("warn", LogLevel.WARN))
        assertEquals(listOf("info", "warn"), all.snapshot().map { it.message })
        assertEquals(listOf("warn"), warnings.snapshot().map { it.message })
    }

    @Test
    fun formatterShowsTheError() {
        // The exception's class name differs per platform, so only the stable parts are checked.
        val text = record("failed", LogLevel.ERROR, IllegalStateException("bad state")).toString()
        assertTrue(text.startsWith("1970-01-01T00:00:01Z E test: failed | error: "))
        assertTrue(text.endsWith("bad state"))
    }

    @Test
    fun consoleSinkPrints() {
        ConsoleSink().write(record("to stdout", error = RuntimeException("with trace")))
    }

    @Test
    fun offIsNotARecordLevel() {
        assertFailsWith<IllegalArgumentException> { record("x", LogLevel.OFF) }
    }
}

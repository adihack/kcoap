package dev.kcoap.log

import kotlin.concurrent.atomics.AtomicArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Keeps the last [capacity] records in memory, so they can be gathered on demand: attached to a bug
 * report, shown in a debug screen, or returned by a diagnostics endpoint.
 *
 * Writing is lock free and never blocks the logging thread. When full, the oldest record is replaced.
 *
 * ```
 * val recent = RingBufferSink(capacity = 2_000)
 * KcoapLogging.configure { level = LogLevel.DEBUG; sink = ConsoleSink() + recent }
 * ...
 * report.attach(recent.dump())
 * ```
 */
@OptIn(ExperimentalAtomicApi::class)
public class RingBufferSink(public val capacity: Int) : LogSink {
    init {
        require(capacity > 0) { "Capacity must be positive: $capacity" }
    }

    private class Entry(val sequence: Long, val record: LogRecord)

    private val slots = AtomicArray(arrayOfNulls<Entry>(capacity))
    private val written = AtomicLong(0)

    override fun write(record: LogRecord) {
        val sequence = written.fetchAndAdd(1)
        slots.storeAt((sequence % capacity).toInt(), Entry(sequence, record))
    }

    /** The retained records, oldest first. */
    public fun snapshot(): List<LogRecord> =
        (0 until capacity)
            .mapNotNull { slots.loadAt(it) }
            .sortedBy { it.sequence }
            .map { it.record }

    /** The retained records as text, one per line, oldest first. */
    public fun dump(formatter: LogFormatter = LogFormatter.Default): String =
        snapshot().joinToString(separator = "\n") { formatter.format(it) }

    /** How many records were overwritten because the buffer was full. */
    public val dropped: Long get() = (written.load() - capacity).coerceAtLeast(0)

    public fun clear() {
        for (i in 0 until capacity) slots.storeAt(i, null)
        written.store(0)
    }
}

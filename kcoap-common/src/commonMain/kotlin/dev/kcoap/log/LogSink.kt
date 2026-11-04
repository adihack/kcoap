package dev.kcoap.log

/**
 * Where log records go. Implement it to forward records to your own logging (SLF4J, Logcat, OSLog,
 * crash reporting), or combine the sinks provided here.
 *
 * [write] is called on the thread that logs, so it must be quick and thread safe. It must not throw;
 * a failing sink should drop the record.
 */
public fun interface LogSink {
    public fun write(record: LogRecord)

    public companion object {
        /** Discards everything. */
        public val None: LogSink = LogSink { }
    }
}

/** Sends each record to this sink and to [other]. */
public operator fun LogSink.plus(other: LogSink): LogSink = CompositeSink(listOf(this, other))

/** Passes on only records at [level] or above, for example to send warnings to a crash reporter. */
public fun LogSink.atLeast(level: LogLevel): LogSink = LogSink { record ->
    if (record.level >= level) write(record)
}

/** Fans each record out to several sinks, in order. */
public class CompositeSink(sinks: List<LogSink>) : LogSink {
    private val sinks: List<LogSink> = sinks.flatMap { if (it is CompositeSink) it.sinks else listOf(it) }

    override fun write(record: LogRecord) {
        for (sink in sinks) sink.write(record)
    }
}

/** Prints each record as one line on standard output: stdout on JVM and native, `console.log` on JS. */
public class ConsoleSink(
    private val formatter: LogFormatter = LogFormatter.Default,
) : LogSink {
    override fun write(record: LogRecord) {
        println(formatter.format(record))
        record.error?.let { println(it.stackTraceToString()) }
    }
}

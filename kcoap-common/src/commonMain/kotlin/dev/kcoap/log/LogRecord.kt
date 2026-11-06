package dev.kcoap.log

import kotlin.time.Instant

/**
 * One log event, as handed to every [LogSink]. Immutable, so sinks may keep it, forward it to another
 * thread, or serialize it.
 *
 * @property tag dot-separated origin such as `coap.exchange` or `oscore`; see [LogTags].
 * @property fields structured context, for example the peer, token or Message ID, already rendered as
 *   strings so that no mutable object escapes into a sink.
 */
public class LogRecord(
    public val timestamp: Instant,
    public val level: LogLevel,
    public val tag: String,
    public val message: String,
    public val fields: Map<String, String> = emptyMap(),
    public val error: Throwable? = null,
) {
    init {
        require(level != LogLevel.OFF) { "OFF is a threshold, not a record level" }
    }

    override fun toString(): String = LogFormatter.Default.format(this)
}

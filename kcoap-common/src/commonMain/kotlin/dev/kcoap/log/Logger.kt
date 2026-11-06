package dev.kcoap.log

import dev.kcoap.common.toHex
import kotlin.concurrent.Volatile

/**
 * Logs for one [tag]. Obtain one from a [LoggerFactory], or from [KcoapLogging.logger] for the global
 * configuration.
 *
 * Messages are lambdas, so nothing is formatted unless the level is enabled:
 *
 * ```
 * log.debug { "Retransmitting MID $mid, attempt $attempt" }
 * log.warn(error) { "Dropped a message from $peer" }
 * ```
 *
 * [with] returns a logger that adds fields to every record, which keeps per-exchange context out of
 * the message text and easy to filter on.
 */
public class Logger internal constructor(
    public val tag: String,
    private val fields: Map<String, String>,
    private val configSource: () -> LogConfig,
) {
    private class Threshold(val config: LogConfig, val level: LogLevel)

    @Volatile
    private var threshold: Threshold? = null

    /** The current configuration; re-read on every call so runtime changes apply immediately. */
    public val config: LogConfig get() = configSource()

    public fun isEnabled(level: LogLevel): Boolean {
        if (level == LogLevel.OFF) return false
        val config = configSource()
        val cached = threshold
        val current = if (cached != null && cached.config === config) {
            cached.level
        } else {
            config.levelFor(tag).also { threshold = Threshold(config, it) }
        }
        return current != LogLevel.OFF && level >= current
    }

    public inline fun trace(error: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.TRACE)) emit(LogLevel.TRACE, message(), error)
    }

    public inline fun debug(error: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.DEBUG)) emit(LogLevel.DEBUG, message(), error)
    }

    public inline fun info(error: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.INFO)) emit(LogLevel.INFO, message(), error)
    }

    public inline fun warn(error: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.WARN)) emit(LogLevel.WARN, message(), error)
    }

    public inline fun error(error: Throwable? = null, message: () -> String) {
        if (isEnabled(LogLevel.ERROR)) emit(LogLevel.ERROR, message(), error)
    }

    /** A logger with the same tag that adds [extra] to every record. */
    public fun with(vararg extra: Pair<String, Any?>): Logger =
        Logger(tag, fields + extra.map { (key, value) -> key to value.toString() }, configSource)

    /** A logger for the sub-tag `tag.name`, keeping this logger's fields. */
    public fun child(name: String): Logger = Logger("$tag.$name", fields, configSource)

    /**
     * Renders message bytes for a log line: hex when [LogConfig.payloadDumps] is on, otherwise only
     * the length. Never pass key material here.
     */
    public fun payload(bytes: ByteArray): String =
        if (config.payloadDumps) "${bytes.size} bytes ${bytes.toHex()}" else "${bytes.size} bytes"

    @PublishedApi
    internal fun emit(level: LogLevel, message: String, error: Throwable?) {
        val config = configSource()
        val record = LogRecord(config.clock.now(), level, tag, message, fields, error)
        try {
            config.sink.write(record)
        } catch (_: Throwable) {
            // A broken sink must never break the protocol code that logs.
        }
    }
}

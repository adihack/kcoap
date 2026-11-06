package dev.kcoap.log

import kotlin.time.Clock

/**
 * What gets logged and where it goes. Immutable: change it by building a new one, usually through
 * [KcoapLogging.configure].
 *
 * Levels are set per tag and inherited by sub-tags: a level for `coap` applies to `coap.exchange`
 * and `coap.message` unless they have their own.
 */
public class LogConfig private constructor(
    /** Threshold for tags without a more specific setting. */
    public val level: LogLevel,
    private val tagLevels: Map<String, LogLevel>,
    public val sink: LogSink,
    /**
     * Whether [Logger.payload] renders message bytes in hex. Off by default because payloads can carry
     * application data; key material is never logged either way.
     */
    public val payloadDumps: Boolean,
    public val clock: Clock,
) {
    /** The threshold that applies to [tag]: its own, else the closest parent tag's, else [level]. */
    public fun levelFor(tag: String): LogLevel {
        var candidate = tag
        while (true) {
            tagLevels[candidate]?.let { return it }
            val dot = candidate.lastIndexOf('.')
            if (dot < 0) return level
            candidate = candidate.substring(0, dot)
        }
    }

    public fun toBuilder(): Builder = Builder(this)

    public class Builder internal constructor(base: LogConfig?) {
        public var level: LogLevel = base?.level ?: LogLevel.WARN
        public var sink: LogSink = base?.sink ?: ConsoleSink()
        public var payloadDumps: Boolean = base?.payloadDumps ?: false
        public var clock: Clock = base?.clock ?: Clock.System
        private val tagLevels: MutableMap<String, LogLevel> = base?.tagLevels?.toMutableMap() ?: mutableMapOf()

        public constructor() : this(null)

        /** Sets the threshold for [tag] and, unless they have their own, its sub-tags. */
        public fun level(tag: String, level: LogLevel): Builder = apply { tagLevels[tag] = level }

        /** Removes a per-tag setting, so the tag inherits again. */
        public fun clearLevel(tag: String): Builder = apply { tagLevels.remove(tag) }

        public fun build(): LogConfig = LogConfig(level, tagLevels.toMap(), sink, payloadDumps, clock)
    }

    public companion object {
        /**
         * WARN and above, printed to the console: what an operator should act on and failures the library could not
         * handle, but no lifecycle or protocol detail, so an app that never configures logging writes nothing to its
         * standard output while all is well. Lower the [level] for more.
         */
        public val Default: LogConfig = Builder().build()

        public fun build(block: Builder.() -> Unit): LogConfig = Builder().apply(block).build()
    }
}

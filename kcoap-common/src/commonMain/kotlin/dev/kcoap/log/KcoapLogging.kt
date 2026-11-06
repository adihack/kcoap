package dev.kcoap.log

import kotlin.concurrent.Volatile

/**
 * Creates [Logger]s that read their configuration from one place.
 *
 * Every kcoap component takes a `LoggerFactory` (defaulting to [Global]), so an application can give
 * one endpoint its own configuration, for example to trace a single connection, while the rest
 * follows the global one.
 */
public class LoggerFactory(private val configSource: () -> LogConfig) {
    /** A factory with a fixed configuration. */
    public constructor(config: LogConfig) : this({ config })

    public fun logger(tag: String): Logger = Logger(tag, emptyMap(), configSource)

    public companion object {
        /** Follows [KcoapLogging.config], including later changes. */
        public val Global: LoggerFactory = LoggerFactory { KcoapLogging.config }
    }
}

/**
 * The global logging configuration.
 *
 * ```
 * KcoapLogging.configure {
 *     level = LogLevel.INFO
 *     level(LogTags.OSCORE, LogLevel.DEBUG)
 *     sink = ConsoleSink() + RingBufferSink(capacity = 1_000)
 * }
 * ```
 */
public object KcoapLogging {
    @Volatile
    private var current: LogConfig = LogConfig.Default

    public val config: LogConfig get() = current

    /** Applies [block] to a copy of the current configuration and makes the result current. */
    public fun configure(block: LogConfig.Builder.() -> Unit) {
        current = current.toBuilder().apply(block).build()
    }

    /** Replaces the configuration. */
    public fun use(config: LogConfig) {
        current = config
    }

    /** Back to [LogConfig.Default]. */
    public fun reset() {
        current = LogConfig.Default
    }

    public fun logger(tag: String): Logger = LoggerFactory.Global.logger(tag)
}

/** The tags kcoap logs under. Sub-tags inherit levels, so `coap` covers `coap.exchange`. */
public object LogTags {
    public const val COAP: String = "coap"
    public const val COAP_MESSAGE: String = "coap.message"
    public const val COAP_EXCHANGE: String = "coap.exchange"
    public const val COAP_SERVER: String = "coap.server"
    public const val COAP_CLIENT: String = "coap.client"
    public const val TRANSPORT: String = "transport"
    public const val OSCORE: String = "oscore"
    public const val EDHOC: String = "edhoc"
    public const val DTLS: String = "dtls"
    public const val CRYPTO: String = "crypto"
}

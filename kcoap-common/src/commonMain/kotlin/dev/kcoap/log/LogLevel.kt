package dev.kcoap.log

/** Severity of a log record, from the most verbose to the most severe. */
public enum class LogLevel(
    /** One-letter form used by [LogFormatter.Default]. */
    public val letter: Char,
) {
    /** Byte-level detail: encoded messages, state-machine steps. */
    TRACE('T'),

    /** Protocol events a developer follows when debugging: exchanges, retransmissions, handshakes. */
    DEBUG('D'),

    /** Lifecycle: endpoints started, sessions established. */
    INFO('I'),

    /** Something was rejected or recovered from: a replayed message, a failed handshake. */
    WARN('W'),

    /** Something broke that the library could not handle. */
    ERROR('E'),

    /** Used only as a threshold, to switch logging off. Records never carry it. */
    OFF('-'),
}

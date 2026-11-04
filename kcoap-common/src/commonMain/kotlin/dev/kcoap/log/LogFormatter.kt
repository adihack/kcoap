package dev.kcoap.log

/** Turns a record into one line of text. */
public fun interface LogFormatter {
    public fun format(record: LogRecord): String

    public companion object {
        /**
         * `2026-11-04T21:00:00.123Z D coap.exchange: Response 2.05 {peer=192.0.2.1:5683, token=4a7b}`,
         * followed by ` | error: <message>` when the record carries an error.
         */
        public val Default: LogFormatter = LogFormatter { record ->
            buildString {
                append(record.timestamp)
                append(' ').append(record.level.letter)
                append(' ').append(record.tag).append(": ")
                append(record.message)
                if (record.fields.isNotEmpty()) {
                    record.fields.entries.joinTo(this, prefix = " {", postfix = "}") { "${it.key}=${it.value}" }
                }
                record.error?.let { append(" | error: ").append(it.toString()) }
            }
        }
    }
}

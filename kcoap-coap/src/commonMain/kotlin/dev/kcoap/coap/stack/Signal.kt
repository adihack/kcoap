package dev.kcoap.coap.stack

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapOption
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.SignalingOptionDefinition
import dev.kcoap.coap.SignalingOptions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A signaling message of CoAP over reliable transports (RFC 8323 §5), as the messaging layer reads and
 * writes it. Option numbers come from the signaling registry ([SignalingOptions]), per code (§5.2).
 */
internal sealed interface Signal {
    /** The message, with [token]; signaling messages have no Type or Message ID on the wire. */
    fun toMessage(token: ByteArray = ByteArray(0)): CoapMessage

    /**
     * 7.01 Capabilities and Settings Message (§5.3). Absent options leave the earlier values in force,
     * since CSM options are cumulative.
     */
    class Csm(val maxMessageSize: Long?, val blockWiseTransfer: Boolean) : Signal {
        override fun toMessage(token: ByteArray): CoapMessage = message(CoapCode.CSM, token) {
            maxMessageSize?.let { add(CoapOption.uint(SignalingOptions.MAX_MESSAGE_SIZE.number, it)) }
            if (blockWiseTransfer) add(CoapOption.empty(SignalingOptions.BLOCK_WISE_TRANSFER.number))
        }
    }

    /** 7.02 Ping (§5.4); with [custody] it asks the receiver to answer after processing what came before. */
    class Ping(val custody: Boolean) : Signal {
        override fun toMessage(token: ByteArray): CoapMessage =
            message(CoapCode.PING, token) { if (custody) add(CoapOption.empty(SignalingOptions.CUSTODY.number)) }
    }

    /** 7.03 Pong (§5.4); [custody] says everything received before the Ping was processed (§5.4.1). */
    class Pong(val custody: Boolean) : Signal {
        override fun toMessage(token: ByteArray): CoapMessage =
            message(CoapCode.PONG, token) { if (custody) add(CoapOption.empty(SignalingOptions.CUSTODY.number)) }
    }

    /** 7.04 Release (§5.5): an orderly shutdown the receiver completes by closing the connection. */
    class Release(
        val alternativeAddresses: List<String> = emptyList(),
        val holdOff: Duration? = null,
        val diagnostic: String? = null,
    ) : Signal {
        override fun toMessage(token: ByteArray): CoapMessage = message(CoapCode.RELEASE, token, diagnostic) {
            alternativeAddresses.forEach { add(CoapOption.string(SignalingOptions.ALTERNATIVE_ADDRESS.number, it)) }
            holdOff?.let { add(CoapOption.uint(SignalingOptions.HOLD_OFF.number, it.inWholeSeconds)) }
        }
    }

    /** 7.05 Abort (§5.6): the sender closes the connection right after it. */
    class Abort(val badCsmOption: Int? = null, val diagnostic: String? = null) : Signal {
        override fun toMessage(token: ByteArray): CoapMessage = message(CoapCode.ABORT, token, diagnostic) {
            badCsmOption?.let { add(CoapOption.uint(SignalingOptions.BAD_CSM_OPTION.number, it.toLong())) }
        }
    }

    /** What [parse] found in a message with a 7.xx code. */
    sealed interface Parsed {
        class Known(val signal: Signal) : Parsed

        /** An unassigned signaling code (§11.1); the message is ignored. */
        data object UnknownCode : Parsed

        /**
         * A message the receiver cannot process and must answer with an Abort (§5.2, §5.6): an unknown
         * critical option, or for a CSM an option it cannot process ([badCsmOption]).
         */
        class Invalid(val reason: String, val badCsmOption: Int?) : Parsed
    }

    companion object {
        /**
         * Reads the signaling message [message]. Elective options the code does not define are ignored,
         * and so are repetitions of a non-repeatable option and, outside a CSM, values of the wrong
         * length (RFC 7252 §5.4.1, §5.4.3, §5.4.5, which §5.2 applies to signaling options). A CSM with a
         * value it cannot process is invalid, because a CSM MUST be valid (§3.3).
         */
        fun parse(message: CoapMessage): Parsed {
            val code = message.code
            if (code !in KNOWN_CODES) return Parsed.UnknownCode
            val accepted = mutableListOf<CoapOption>()
            for (option in message.options) {
                val definition = SignalingOptions.byNumber(code, option.number)
                if (definition == null) {
                    if (option.number and 1 == 0) continue
                    return Parsed.Invalid(
                        "Unknown critical option ${option.number} in $code",
                        badCsmOption = option.number.takeIf { code == CoapCode.CSM },
                    )
                }
                if (option.length !in definition.minLength..definition.maxLength) {
                    if (code == CoapCode.CSM) return Parsed.Invalid("Invalid length of $definition in a CSM", option.number)
                    continue
                }
                if (!definition.repeatable && accepted.any { it.number == option.number }) continue
                accepted += option
            }
            val options = OptionSet.of(accepted)
            fun has(definition: SignalingOptionDefinition) = definition.number in options
            fun uint(definition: SignalingOptionDefinition) = options.first(definition.number)?.uintValue()
            val diagnostic = message.payload.takeIf { it.isNotEmpty() }?.decodeToString()
            val signal = when (code) {
                CoapCode.CSM -> Csm(uint(SignalingOptions.MAX_MESSAGE_SIZE), has(SignalingOptions.BLOCK_WISE_TRANSFER))
                CoapCode.PING -> Ping(has(SignalingOptions.CUSTODY))
                CoapCode.PONG -> Pong(has(SignalingOptions.CUSTODY))
                CoapCode.RELEASE -> Release(
                    options.all(SignalingOptions.ALTERNATIVE_ADDRESS.number).map { it.stringValue() },
                    uint(SignalingOptions.HOLD_OFF)?.seconds,
                    diagnostic,
                )
                else -> Abort(uint(SignalingOptions.BAD_CSM_OPTION)?.toInt(), diagnostic)
            }
            return Parsed.Known(signal)
        }

        private val KNOWN_CODES = setOf(CoapCode.CSM, CoapCode.PING, CoapCode.PONG, CoapCode.RELEASE, CoapCode.ABORT)

        private fun message(code: CoapCode, token: ByteArray, diagnostic: String? = null, options: MutableList<CoapOption>.() -> Unit) =
            CoapMessage.build {
                this.code = code
                this.token = token
                options { addAll(mutableListOf<CoapOption>().apply(options)) }
                diagnostic?.let { payload(it) }
            }
    }
}

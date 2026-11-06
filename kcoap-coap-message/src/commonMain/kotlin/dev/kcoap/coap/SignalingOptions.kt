package dev.kcoap.coap

import dev.kcoap.common.toHex

/**
 * A Signaling Option (RFC 8323 §5.2, §11.2). Signaling option numbers are scoped to their signaling
 * code: 2 is Max-Message-Size in a CSM, Custody in a Ping or Pong, Alternative-Address in a Release and
 * Bad-CSM-Option in an Abort. They share no number space with the request and response options of
 * [CoapOptions]. Critical or elective follows from the number as in RFC 7252 §5.4.6.
 *
 * @property codes the signaling codes the option applies to.
 */
public class SignalingOptionDefinition(
    public val codes: Set<CoapCode>,
    public val number: Int,
    public val name: String,
    public val format: OptionFormat,
    public val minLength: Int,
    public val maxLength: Int,
    public val repeatable: Boolean,
    public val reference: String = "RFC 8323",
) {
    init {
        require(codes.isNotEmpty() && codes.all { it.isSignaling }) { "Signaling options apply to signaling codes: $codes" }
    }

    public val isCritical: Boolean get() = (number and 1) != 0

    override fun toString(): String = "$name($number)"
}

/** The CoAP Signaling Option Numbers registry of RFC 8323 §11.2. */
public object SignalingOptions {
    /** The largest message the sender of a CSM can receive (§5.3.1); its base value is [BASE_MAX_MESSAGE_SIZE]. */
    public val MAX_MESSAGE_SIZE: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.CSM), 2, "Max-Message-Size", OptionFormat.UINT, 0, 4, false)

    /** The sender of a CSM supports RFC 7959, and BERT when its Max-Message-Size exceeds 1152 (§5.3.2). */
    public val BLOCK_WISE_TRANSFER: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.CSM), 4, "Block-Wise-Transfer", OptionFormat.EMPTY, 0, 0, false)

    /** In a Pong: everything received before the Ping was processed; in a Ping: please say so (§5.4.1). */
    public val CUSTODY: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.PING, CoapCode.PONG), 2, "Custody", OptionFormat.EMPTY, 0, 0, false)

    /** Where to connect instead, as an RFC 3986 authority (§5.5); repeatable. */
    public val ALTERNATIVE_ADDRESS: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.RELEASE), 2, "Alternative-Address", OptionFormat.STRING, 1, 255, true)

    /** Seconds the peer should wait before reconnecting (§5.5). */
    public val HOLD_OFF: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.RELEASE), 4, "Hold-Off", OptionFormat.UINT, 0, 3, false)

    /** The CSM option the sender of an Abort could not process (§5.6). */
    public val BAD_CSM_OPTION: SignalingOptionDefinition =
        SignalingOptionDefinition(setOf(CoapCode.ABORT), 2, "Bad-CSM-Option", OptionFormat.UINT, 0, 2, false)

    /** Every registered signaling option. */
    public val all: List<SignalingOptionDefinition> =
        listOf(MAX_MESSAGE_SIZE, BLOCK_WISE_TRANSFER, CUSTODY, ALTERNATIVE_ADDRESS, HOLD_OFF, BAD_CSM_OPTION)

    /** RFC 8323 §5.3.1 (after RFC 7252 §4.6): the Max-Message-Size assumed until a CSM says otherwise. */
    public const val BASE_MAX_MESSAGE_SIZE: Long = 1152

    /** The option [number] means in a message with signaling [code], or null if it is not registered for it. */
    public fun byNumber(code: CoapCode, number: Int): SignalingOptionDefinition? =
        all.firstOrNull { it.number == number && code in it.codes }

    /** `Max-Message-Size: 1152`, `Custody`, or `8: 0a` for an option [code] does not define. */
    internal fun describe(code: CoapCode, option: CoapOption): String {
        val definition = byNumber(code, option.number) ?: return "${option.number}: ${option.value.toHex()}"
        val rendered = when (definition.format) {
            OptionFormat.EMPTY -> ""
            OptionFormat.STRING -> option.stringValue()
            OptionFormat.UINT -> if (option.length <= 7) option.uintValue().toString() else option.value.toHex()
            OptionFormat.OPAQUE -> option.value.toHex()
        }
        return if (rendered.isEmpty()) definition.name else "${definition.name}: $rendered"
    }
}

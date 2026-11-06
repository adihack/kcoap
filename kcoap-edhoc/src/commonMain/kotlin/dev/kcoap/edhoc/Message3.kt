package dev.kcoap.edhoc

/**
 * What [EdhocInitiator.createMessage3] returns: message_3 to send (RFC 9528 §5.4.2) and the session it
 * completes, so no caller has to read a nullable [EdhocInitiator.session].
 *
 * ```
 * val (message3, session) = initiator.createMessage3()
 * ```
 *
 * RFC 9528 §5.4.2 advises the Initiator not to persist the session's keys until message_4, or a protected
 * message from the Responder, has been verified.
 */
public class Message3 internal constructor(
    bytes: ByteArray,
    /** The completed session: PRK_out, the exporter, the authenticated Responder. */
    public val session: EdhocSession,
) {
    private val encoded = bytes.copyOf()

    /** A copy of the message_3 bytes to send to the Responder. */
    public val bytes: ByteArray get() = encoded.copyOf()

    public operator fun component1(): ByteArray = bytes

    public operator fun component2(): EdhocSession = session

    override fun toString(): String = "Message3(${encoded.size} bytes, $session)"
}

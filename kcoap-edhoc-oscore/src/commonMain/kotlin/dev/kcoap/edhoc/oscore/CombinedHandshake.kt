package dev.kcoap.edhoc.oscore

/**
 * What [EdhocClientHandshake.runCombined] returns (RFC 9668 §3.2): the established [session] and the
 * [security] whose first protected request carries message_3. Destructure it, or install [security] for
 * the server with [PeerSecurity.install]:
 *
 * ```
 * val (session, security) = handshake.runCombined()
 * ```
 *
 * @property session the EDHOC session and the OSCORE context derived from it.
 * @property security protects requests with that context; the first one also completes the handshake on
 *   the server.
 */
public class CombinedHandshake(public val session: EdhocOscoreSession, public val security: CombinedRequestSecurity) {
    /** [session], for destructuring. */
    public operator fun component1(): EdhocOscoreSession = session

    /** [security], for destructuring. */
    public operator fun component2(): CombinedRequestSecurity = security
}

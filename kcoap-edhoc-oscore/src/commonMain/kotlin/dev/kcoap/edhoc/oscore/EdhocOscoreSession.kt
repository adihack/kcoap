package dev.kcoap.edhoc.oscore

import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.oscore.SecurityContext

/**
 * An established EDHOC session and the OSCORE context derived from it (RFC 9528 Appendix A.1), as
 * [EdhocClientHandshake] returns them.
 *
 * @property edhoc the session, for its peer credential, exporter and key update.
 * @property oscore the client's OSCORE context; its Sender ID is the server's C_R.
 */
public class EdhocOscoreSession(public val edhoc: EdhocSession, public val oscore: SecurityContext)

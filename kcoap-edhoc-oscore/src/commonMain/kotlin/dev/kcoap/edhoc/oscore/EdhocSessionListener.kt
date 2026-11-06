package dev.kcoap.edhoc.oscore

import dev.kcoap.edhoc.EdhocSession
import dev.kcoap.oscore.SecurityContext

/**
 * Called by an [EdhocServer] when an EDHOC session completes and its OSCORE context is in the server's
 * repository (RFC 9528 Appendix A.1). Pending sessions it refused or dropped go to [EdhocServerEventListener].
 */
public fun interface EdhocSessionListener {
    /** [session] completed, and [context], derived from it, now verifies its peer's requests. */
    public suspend fun onEstablished(session: EdhocSession, context: SecurityContext)
}

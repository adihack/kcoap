package dev.kcoap.sample.js

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex

/**
 * The input a client needs to derive its OSCORE context (RFC 8613 §3.2): a Master Secret, its own Sender ID and
 * its Recipient ID, the server's Sender ID.
 *
 * The sample's server makes a new one for every page load and hands it to the page over the page's own
 * connection (HTTPS in production), as an ACE authorization server provisions OSCORE input material (RFC 9203).
 * A browser cannot keep the sequence numbers of a pre-shared Master Secret on durable storage, so reusing one
 * across page loads would reuse nonces (RFC 8613 Appendix B.1.1); keys that are new for each page never outlive
 * the page, and an ephemeral sequence-number store is then right.
 */
class OscoreSession(val masterSecret: ByteArray, val senderId: ByteArray, val recipientId: ByteArray) {
    /** The text form: the three values in hexadecimal, separated by single spaces; an empty ID is empty. */
    fun encode(): String = listOf(masterSecret, senderId, recipientId).joinToString(" ") { it.toHex() }

    companion object {
        /**
         * Reads [encode]'s text form.
         *
         * @throws IllegalArgumentException when [text] is not three hexadecimal values separated by single spaces.
         */
        fun decode(text: String): OscoreSession {
            val parts = text.split(' ')
            require(parts.size == 3) { "An OSCORE session is three hexadecimal values: '$text'" }
            return OscoreSession(parts[0].hexToBytes(), parts[1].hexToBytes(), parts[2].hexToBytes())
        }
    }
}

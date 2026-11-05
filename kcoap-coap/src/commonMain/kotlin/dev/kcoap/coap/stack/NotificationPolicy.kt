package dev.kcoap.coap.stack

import kotlin.time.Duration

/**
 * Whether each notification of one observation goes Confirmable, as its [PeerLink] decides
 * ([PeerLink.notificationPolicy]). Only a link with message types has a choice to make (RFC 7641 §4.5, §7);
 * a connection writes every notification as it is (RFC 8323 §7.2). Not thread safe: the observation calls it
 * under its own lock.
 */
internal sealed interface NotificationPolicy {
    /**
     * Whether the next notification goes Confirmable at [now], for a resource that asks for Confirmable
     * notifications when [requested] ([dev.kcoap.coap.server.ObservableResource.notificationType]).
     */
    fun nextIsConfirmable(requested: Boolean, now: Duration): Boolean

    /** A notification, or the registration response, went out at [now]: Confirmable when [confirmable]. */
    fun sent(confirmable: Boolean, now: Duration)

    /**
     * RFC 7641 §4.5 and §7 on a datagram link: a resource may ask for Non-confirmable notifications, but a
     * Confirmable one still goes out at least every [confirmableInterval], whose ACK shows that the client
     * still wants them, and to a client that neither message nor channel security authenticated
     * ([isAuthenticated] false) at least every [UNAUTHENTICATED_NON_CONFIRMABLE_RUN] + 1st notification, since
     * a spoofed registration does not acknowledge.
     */
    class Interspersed(
        registeredAt: Duration,
        private val isAuthenticated: Boolean,
        private val confirmableInterval: Duration,
    ) : NotificationPolicy {
        private var lastConfirmableAt = registeredAt

        /** Non-confirmable notifications since the last Confirmable one. */
        private var nonConfirmableInRow = 0

        override fun nextIsConfirmable(requested: Boolean, now: Duration): Boolean =
            requested || now - lastConfirmableAt >= confirmableInterval ||
                (!isAuthenticated && nonConfirmableInRow >= UNAUTHENTICATED_NON_CONFIRMABLE_RUN)

        override fun sent(confirmable: Boolean, now: Duration) {
            if (confirmable) {
                lastConfirmableAt = now
                nonConfirmableInRow = 0
            } else {
                nonConfirmableInRow++
            }
        }

        private companion object {
            /** At most this many Non-confirmable notifications in a row to an unauthenticated client (RFC 7641 §7). */
            const val UNAUTHENTICATED_NON_CONFIRMABLE_RUN = 8
        }
    }

    /**
     * A link without message types (RFC 8323 §7.2): nothing to choose and nothing to count. The resource's
     * choice is passed through as a hint, which the link ignores.
     */
    data object Untyped : NotificationPolicy {
        override fun nextIsConfirmable(requested: Boolean, now: Duration): Boolean = requested

        override fun sent(confirmable: Boolean, now: Duration) = Unit
    }
}

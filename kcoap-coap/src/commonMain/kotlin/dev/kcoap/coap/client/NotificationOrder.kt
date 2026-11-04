package dev.kcoap.coap.client

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * RFC 7641 §3.4: which notification is the freshest, whatever the order of arrival. Keeps the Observe
 * value V1 and local arrival time T1 of the freshest notification so far.
 */
internal class NotificationOrder {
    private var freshestValue: Long? = null
    private var freshestTime: Duration = Duration.ZERO

    /**
     * Whether a notification with Observe [value] arriving at [now] was sent more recently than the
     * freshest so far, by 24-bit serial number arithmetic (RFC 1982) or by 128 seconds of silence; if so
     * it becomes the freshest.
     */
    fun accept(value: Long, now: Duration): Boolean {
        val v1 = freshestValue
        val fresher = v1 == null || isNewer(v1, value) || now > freshestTime + SILENCE
        if (fresher) {
            freshestValue = value
            freshestTime = now
        }
        return fresher
    }

    companion object {
        private const val HALF: Long = 1L shl 23
        private val SILENCE = 128.seconds

        /** (V1 < V2 and V2 - V1 < 2^23) or (V1 > V2 and V1 - V2 > 2^23). */
        fun isNewer(v1: Long, v2: Long): Boolean = (v1 < v2 && v2 - v1 < HALF) || (v1 > v2 && v1 - v2 > HALF)
    }
}

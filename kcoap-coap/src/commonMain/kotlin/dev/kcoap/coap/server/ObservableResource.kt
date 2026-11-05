package dev.kcoap.coap.server

import dev.kcoap.coap.CoapMessageType
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A resource clients can observe (RFC 7641). A GET or FETCH with Observe 0 that the handler answers with
 * 2.xx adds the client to the observers; call [notifyObservers] whenever the state changes, and every
 * observer gets a notification produced by calling the handler again with its registration request.
 *
 * ```
 * class Clock : ObservableResource("time") {
 *     override suspend fun handleGet(context: RequestContext) =
 *         CoapResponse.content(now()).withOptions { maxAge(1) }
 * }
 * // every second: clock.notifyObservers()
 * ```
 *
 * The server takes care of the rest of RFC 7641 §4: sequence numbers (§4.4), at most NSTART outstanding
 * notifications per client, sending only the latest state when changes outpace the network and
 * superseding an unacknowledged notification (§4.5.2), a Confirmable notification at least every
 * [CoapServerConfig.confirmableNotificationInterval] (§4.5), removing observers on RST, timeout or a
 * non-2.xx notification (§3.2, §4.5), and 4.04 notifications when the resource is removed. Over a reliable
 * transport (RFC 8323 §7) the notifications go on the registration's connection, in order, and a closed
 * connection removes its observers.
 */
@OptIn(ExperimentalAtomicApi::class)
public abstract class ObservableResource(name: String) : CoapResource(name) {
    private val sequence = AtomicLong(0)

    override val isObservable: Boolean get() = true

    /**
     * Whether notifications go Confirmable or Non-confirmable (RFC 7641 §4.5): a hint for datagram transports
     * only, since a reliable transport has no message types and ignores it (RFC 8323 §7.2). Non-confirmable
     * ones are cheaper for frequent changes; the server still sends a Confirmable one periodically, and to a
     * client that neither message nor channel security authenticated at least every ninth notification, whose
     * ACK shows the client still wants them (RFC 7641 §7). Only [CoapMessageType.NON] asks for Non-confirmable
     * notifications; any other value asks for Confirmable ones.
     */
    public open val notificationType: CoapMessageType get() = CoapMessageType.CON

    /** Tells the observers that the state changed. Returns at once; notifications are sent in the background. */
    public fun notifyObservers() {
        root.server?.observations?.changed(this)
    }

    /** How many clients observe this resource now. */
    public val observerCount: Int get() = root.server?.observations?.count(this) ?: 0

    /**
     * The next Observe value (RFC 7641 §4.4): the 24 low bits of a counter that increases with every
     * notification of this resource, so values towards each client strictly increase, also across
     * re-registrations, and never by more than 2^23 in 256 seconds below 32768 notifications a second.
     */
    internal fun nextSequence(): Long = sequence.fetchAndAdd(1) and 0xFFFFFF

    override fun onRemoved(server: CoapServer?) {
        server?.observations?.removed(this)
        super.onRemoved(server)
    }
}

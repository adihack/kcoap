package dev.kcoap.coap.server

import dev.kcoap.coap.stack.CoapException
import dev.kcoap.coap.stack.DeliveryOutcome
import dev.kcoap.coap.stack.EndpointEvent.ObserverRemoved.Reason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Why a notification's delivery removes its observer (RFC 7641 §4.5), as [ObservationRegistry.removalFor] reports it
 * in `EndpointEvent.ObserverRemoved`: one bounded reason per outcome, so a metric can count by it.
 */
class ObserverRemovalTest {
    private fun failed(reason: CoapException.Reason) = DeliveryOutcome.Failed(CoapException("Failed: $reason", reason))

    /** RFC 7641 §4.5: a Reset, or a Confirmable notification never acknowledged, removes the observer. */
    @Test
    fun rfc7641Section4_5AResetOrNoAcknowledgementRemovesTheObserver() {
        assertEquals(Reason.RESET to null, ObservationRegistry.removalFor(DeliveryOutcome.Rejected))
        assertEquals(Reason.NOT_ACKNOWLEDGED to null, ObservationRegistry.removalFor(DeliveryOutcome.TimedOut))
    }

    /** RFC 7252 §9.1.1, RFC 8323 §7.4: an observation ends with its DTLS session or its connection. */
    @Test
    fun aDeliveryThatFailedBecauseItsSessionOrConnectionEndedIsSessionEnded() {
        assertEquals(
            Reason.SESSION_ENDED to "Failed: SESSION_ENDED",
            ObservationRegistry.removalFor(failed(CoapException.Reason.SESSION_ENDED)),
        )
        assertEquals(
            Reason.SESSION_ENDED to "Failed: CONNECTION_CLOSED",
            ObservationRegistry.removalFor(failed(CoapException.Reason.CONNECTION_CLOSED)),
        )
    }

    @Test
    fun anyOtherFailedDeliveryIsFailedWithItsMessageAsDetail() {
        for (reason in CoapException.Reason.entries) {
            if (reason == CoapException.Reason.SESSION_ENDED || reason == CoapException.Reason.CONNECTION_CLOSED) continue
            assertEquals(Reason.FAILED to "Failed: $reason", ObservationRegistry.removalFor(failed(reason)), "$reason")
        }
    }

    @Test
    fun aDeliveredOrReleasedNotificationRemovesNobody() {
        assertNull(ObservationRegistry.removalFor(DeliveryOutcome.Delivered(null)))
        assertNull(ObservationRegistry.removalFor(DeliveryOutcome.Released))
    }
}

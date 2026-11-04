package com.example.kcoap.dtls

import com.example.kcoap.transport.Endpoint
import com.example.kcoap.transport.SessionEndReason
import kotlin.time.Duration

/**
 * Sans-IO DTLS 1.2 engine for one local datagram socket. **Interface only**: the implementation is a
 * separate, later feature (see docs/architecture.md).
 *
 * Datagrams and time go in, [DtlsEffect]s come out, so the same engine runs under every transport
 * adapter, inside a server event loop, and in tests with virtual time and fixed randomness. A
 * [com.example.kcoap.transport.DatagramSecurity] implementation drives it and turns the effects into
 * [com.example.kcoap.transport.DatagramEvent]s.
 *
 * [now] is monotonic time from an arbitrary origin; every call uses the same clock.
 */
public interface DtlsEngine {
    /** Processes one datagram received from [from]. */
    public suspend fun onDatagram(now: Duration, from: Endpoint, datagram: ByteArray): List<DtlsEffect>

    /**
     * Sends application data. To an [Endpoint.Secure], within that session and epoch. To a plain
     * endpoint, over the established session with it, or after a client handshake it starts.
     */
    public suspend fun send(now: Duration, to: Endpoint, data: ByteArray): List<DtlsEffect>

    /** Called at or after the time of the last [DtlsEffect.ScheduleTimer]. */
    public suspend fun onTimer(now: Duration): List<DtlsEffect>

    /** Sends `close_notify` and ends the session. */
    public suspend fun close(now: Duration, session: Endpoint.Secure): List<DtlsEffect>
}

public sealed interface DtlsEffect {
    /** Put [datagram] on the wire to [to], a plain transport endpoint. */
    public class Transmit(public val to: Endpoint, public val datagram: ByteArray) : DtlsEffect

    /** Decrypted application data, for the CoAP messaging layer. */
    public class Deliver(public val from: Endpoint.Secure, public val data: ByteArray) : DtlsEffect

    /** Call [DtlsEngine.onTimer] at [at]. Replaces any earlier timer. */
    public class ScheduleTimer(public val at: Duration) : DtlsEffect

    public class SessionEstablished(public val endpoint: Endpoint.Secure) : DtlsEffect

    public class SessionEnded(public val endpoint: Endpoint.Secure, public val reason: SessionEndReason) : DtlsEffect

    /** Data passed to [DtlsEngine.send] that will never be sent, for example because the handshake failed. */
    public class SendFailed(public val to: Endpoint, public val data: ByteArray, public val cause: Throwable) : DtlsEffect
}

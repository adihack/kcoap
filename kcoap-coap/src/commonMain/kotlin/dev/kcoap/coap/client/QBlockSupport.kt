package dev.kcoap.coap.client

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapException
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.stack.QBlockCheck
import dev.kcoap.coap.stack.QBlockClientEvent
import dev.kcoap.log.Logger
import dev.kcoap.transport.Endpoint
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What a client knows about whether its servers support Q-Block (RFC 9177 §4.1), per server and per
 * protection (with or without message security), and the probe that finds it out.
 *
 * In [QBlockMode.PROBE] the first request to a server waits for one Confirmable `GET` of
 * [QBlockClientConfig.probePath] with Q-Block2 0/0/16 (block 0 of 16 bytes, nothing more), which §4.1 requires
 * to be Confirmable and which is the probe libcoap sends. Concurrent requests to the same server share it.
 * - A 2.xx answer carrying Q-Block1 or Q-Block2, and no Block option next to it, means
 *   [Capability.SUPPORTED].
 * - Any other answer means [Capability.UNSUPPORTED]: 4.02 (Bad Option, §4.1), even one that repeats the
 *   Q-Block2 option it rejects, as libcoap's does; a Reset; a Block2 answer; or anything else.
 * - No answer leaves it [Capability.UNKNOWN]: requests use RFC 7959, and the probe is repeated after
 *   min(1 minute, [QBlockClientConfig.capabilityLifetime]).
 *
 * The answer is kept for [QBlockClientConfig.capabilityLifetime]. It is forgotten ([invalidate]) when the
 * server's DTLS session ends or its connection closes, when a Non-confirmable body to it got no response at
 * all, and ([rejected]) when it answers a Q-Block message with 4.02 or a Reset. In [QBlockMode.ASSUME_SUPPORTED]
 * nothing is probed and every server counts as supported, except that a 4.02 or a Reset to a Q-Block message
 * marks the server as not supporting it for the lifetime, since that mode has no probe to ask again.
 *
 * Entries are bounded by [MAX_ENTRIES]; the oldest goes first. Safe to use from any coroutine.
 */
internal class QBlockSupport(
    private val client: CoapClient,
    private val config: QBlockClientConfig,
    private val log: Logger,
) {
    enum class Capability { SUPPORTED, UNSUPPORTED, UNKNOWN }

    private data class Key(val peer: Endpoint, val isProtected: Boolean)

    private class Entry(val capability: Capability, val until: Duration)

    /** Guards [entries] and [probes]; never held across a suspension. */
    private val lock = Mutex()
    private val entries = LinkedHashMap<Key, Entry>()
    private val probes = HashMap<Key, CompletableDeferred<Capability>>()

    private val endpoint get() = client.endpoint

    /**
     * Whether [peer] supports Q-Block for requests protected or not ([isProtected]), probing it first in
     * [QBlockMode.PROBE] if nothing is known.
     *
     * @throws CoapException when the probe fails for another reason than a Reset or a timeout, such as the
     *   endpoint closing.
     */
    suspend fun capability(peer: Endpoint, isProtected: Boolean): Capability {
        val key = Key(peer, isProtected)
        var isOwner = false
        val probe = lock.withLock {
            val known = entries[key]
            if (known != null) {
                if (known.until > endpoint.now()) return known.capability
                entries.remove(key)
            }
            if (config.mode == QBlockMode.ASSUME_SUPPORTED) return Capability.SUPPORTED
            probes.getOrPut(key) {
                isOwner = true
                CompletableDeferred()
            }
        }
        if (!isOwner) return probe.await()
        var result: Capability? = null
        try {
            val (capability, reason) = probe(peer)
            result = capability
            record(key, capability, reason)
            return capability
        } catch (e: Throwable) {
            // A failed probe fails its waiters too; a cancelled one lets them fall back to RFC 7959 once.
            if (e is CoapException) probe.completeExceptionally(e)
            throw e
        } finally {
            withContext(NonCancellable) {
                lock.withLock { probes.remove(key) }
                probe.complete(result ?: Capability.UNKNOWN)
            }
        }
    }

    /** The capability known for [peer] without probing, or null; for tests. */
    suspend fun known(peer: Endpoint, isProtected: Boolean): Capability? = lock.withLock {
        entries[Key(peer, isProtected)]?.takeIf { it.until > endpoint.now() }?.capability
    }

    /** How many servers the client remembers; for tests. */
    suspend fun size(): Int = lock.withLock { entries.size }

    /** Forgets what is known about [peer], with and without protection. */
    suspend fun invalidate(peer: Endpoint) {
        val removed = lock.withLock { entries.keys.removeAll { it.peer == peer } }
        if (removed) log.debug { "Forgot whether $peer supports Q-Block" }
    }

    /**
     * [peer] answered a Q-Block message with 4.02 or a Reset ([reason]): in [QBlockMode.PROBE] the probe's
     * answer is forgotten, so the next request asks again; in [QBlockMode.ASSUME_SUPPORTED] the peer counts as
     * not supporting Q-Block for the capability lifetime.
     */
    suspend fun rejected(peer: Endpoint, isProtected: Boolean, reason: String) {
        if (config.mode == QBlockMode.ASSUME_SUPPORTED) {
            record(Key(peer, isProtected), Capability.UNSUPPORTED, reason)
        } else {
            invalidate(peer)
            endpoint.report(QBlockClientEvent.Fallback(peer, reason))
        }
    }

    private suspend fun record(key: Key, capability: Capability, reason: String?) {
        val lifetime = if (capability == Capability.UNKNOWN) minOf(UNKNOWN_LIFETIME, config.capabilityLifetime) else config.capabilityLifetime
        lock.withLock {
            val now = endpoint.now()
            entries.remove(key)
            entries.values.removeAll { it.until <= now }
            while (entries.size >= MAX_ENTRIES) entries.remove(entries.keys.first())
            entries[key] = Entry(capability, now + lifetime)
        }
        log.debug { "${key.peer} ${if (key.isProtected) "(protected) " else ""}Q-Block support: $capability" }
        if (reason != null) endpoint.report(QBlockClientEvent.Fallback(key.peer, reason))
    }

    /** The probe and its verdict, with the reason for a fallback. */
    private suspend fun probe(peer: Endpoint): Pair<Capability, String?> {
        val request = CoapMessage.request(CoapCode.GET) {
            options {
                uriPath(config.probePath)
                qBlock2(BlockOption(0, false, 0))
            }
        }
        val response = try {
            client.exchange(peer, request, confirmable = true, recognized = QBlockClient.RECOGNIZED_OPTIONS)
        } catch (e: CoapException) {
            return when (e.reason) {
                CoapException.Reason.RESET -> Capability.UNSUPPORTED to "The Q-Block probe was answered with a Reset"
                CoapException.Reason.BAD_RESPONSE -> Capability.UNSUPPORTED to "The Q-Block probe's answer was rejected: ${e.message}"
                CoapException.Reason.TRANSMISSION_TIMEOUT, CoapException.Reason.RESPONSE_TIMEOUT ->
                    Capability.UNKNOWN to "The Q-Block probe got no answer"
                else -> throw e
            }
        }
        val options = response.options
        val hasQBlock = options.qBlock1 != null || options.qBlock2 != null
        return when {
            // libcoap without Q-Block answers 4.02 and repeats the option it did not recognize.
            !response.code.isSuccess -> Capability.UNSUPPORTED to "The Q-Block probe was answered ${response.code}"
            !hasQBlock -> Capability.UNSUPPORTED to "The Q-Block probe was answered ${response.code} without Q-Block"
            QBlockCheck.response(response, allowsBert = true) != null ->
                Capability.UNSUPPORTED to "The Q-Block probe's answer mixes Q-Block and Block options"
            else -> Capability.SUPPORTED to null
        }
    }

    override fun toString(): String = "QBlockSupport(${config.mode})"

    private companion object {
        /** How many servers are remembered at most. */
        const val MAX_ENTRIES = 1024

        /** How soon a probe that got no answer is repeated, at most (RFC 9177 §4.1 leaves it open). */
        val UNKNOWN_LIFETIME: Duration = 60.seconds
    }
}

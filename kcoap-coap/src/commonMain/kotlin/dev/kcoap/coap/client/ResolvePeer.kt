package dev.kcoap.coap.client

import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.stack.CoapException
import dev.kcoap.common.InternalKcoapApi
import dev.kcoap.transport.Endpoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * The peer for [uri] through this client's [CoapClientConfig.resolver], with the resolver's failures translated as
 * the [EndpointResolver] contract says: a [CoapException], such as [CoapException.Reason.UNRESOLVED_HOST], and an
 * `IllegalArgumentException` for a host the resolver cannot resolve by design pass unchanged; any other exception
 * is a lookup that failed at runtime, [CoapException.Reason.UNRESOLVED_HOST] with it as cause. That includes a
 * cancellation of the resolver's own, such as its `withTimeout` expiring, while the caller is still active; the
 * caller's cancellation passes unchanged.
 *
 * The client's requests resolve through it, and so does kcoap-edhoc-oscore's `PeerSecurity.install`, which finds
 * the endpoint those requests go to, so that a platform's DNS failure reaches either as the same [CoapException]
 * rather than as an exception that no `@Throws` lists. The host and port are those of [uri] (RFC 7252 §6.4).
 */
@InternalKcoapApi
public suspend fun CoapClient.resolvePeer(uri: CoapUri): Endpoint =
    try {
        config.resolver.resolve(uri)
    } catch (e: CoapException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: Exception) {
        if (e is CancellationException && !currentCoroutineContext().isActive) throw e
        throw CoapException("Cannot resolve ${uri.host}: ${e.message}", CoapException.Reason.UNRESOLVED_HOST, e)
    }

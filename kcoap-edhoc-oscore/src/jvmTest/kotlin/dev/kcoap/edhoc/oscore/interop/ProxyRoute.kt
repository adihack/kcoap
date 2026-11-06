package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapUri

/**
 * Sends the requests a client states for an origin server through the CoAP forward-proxy at [proxy] (RFC 7252
 * §5.7.2), as a browser that reaches devices only through a WebSocket gateway must. kcoap's client has no proxy
 * setting, so the tests route each request with this.
 *
 * [viaProxyScheme] names the target the way RFC 7252 §5.10.2 decomposes a Proxy-Uri: Proxy-Scheme and Uri-Host,
 * Uri-Port when it is not the scheme's default, and Uri-Path and Uri-Query as they are. Under OSCORE, Proxy-Scheme,
 * Uri-Host and Uri-Port are Class U and stay outside for the proxy, and the path is encrypted (RFC 8613 §4.1.3.3).
 * [viaProxyUri] names it in one Proxy-Uri option instead, which kcoap-oscore decomposes in the same way but sends
 * with an outer Proxy-Uri holding the scheme and authority (RFC 8613 §4.1.3.3).
 */
internal class ProxyRoute(private val proxy: CoapUri) {
    /** [request] sent to the proxy, its target named by Proxy-Scheme, Uri-Host and Uri-Port. */
    fun viaProxyScheme(request: CoapRequest): CoapRequest {
        val target = request.uri
        val toProxy = CoapUri(proxy.scheme, proxy.host, proxy.port, target.pathSegments, target.querySegments)
        return CoapRequest.Builder(request.method, toProxy)
            .apply {
                options {
                    addAll(request.options)
                    proxyScheme(target.scheme.scheme)
                    uriHost(target.host)
                    if (target.port != target.scheme.defaultPort) uriPort(target.port)
                }
                payload = request.payload
                isConfirmable = request.isConfirmable
            }.build()
    }

    /** [request] sent to the proxy, its target named by one Proxy-Uri option. */
    fun viaProxyUri(request: CoapRequest): CoapRequest =
        CoapRequest.Builder(request.method, CoapUri(proxy.scheme, proxy.host, proxy.port)).apply {
            options {
                addAll(request.options)
                proxyUri(request.uri.toString())
            }
            payload = request.payload
            isConfirmable = request.isConfirmable
        }.build()
}

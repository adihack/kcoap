package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapUri

/**
 * Sends the requests a client states for an origin server through the CoAP forward-proxy at [proxy] (RFC 7252
 * §5.7.2), as a browser that reaches devices only through a WebSocket gateway must. kcoap's client has no proxy
 * setting, so the tests route each request with this.
 *
 * [viaProxyScheme] names the target with Proxy-Scheme and Uri-Host, and Uri-Path and Uri-Query as they are
 * (RFC 7252 §5.10.2). Under OSCORE, Proxy-Scheme, Uri-Host and Uri-Port are Class U and stay outside for the
 * proxy, and the path is encrypted (RFC 8613 §4.1.3.3).
 *
 * It deviates from RFC 7252 on Uri-Port, for libcoap 4.3.5b: it adds one only when the target's port is not the
 * scheme's default. A proxy composes the target as §6.5 says, where a missing Uri-Port stands for the request's
 * destination port, here the proxy's own, so §6.4 step 7 calls for Uri-Port 5683 whenever the proxy listens
 * elsewhere, as it does. Leaving it out avoids libcoap's repeated Uri-Port, which drops every later option
 * (docs/adr/0009-browser-topology-interop.md, item 3), and relies on the proxy defaulting a missing Uri-Port to
 * the Proxy-Scheme's port, as libcoap does (`coap_get_uri_proxy_scheme_info`). A client proxy setting
 * (checklist coap-core-7) must follow §6.4 step 7 instead.
 *
 * [viaProxyUri] names the target in one Proxy-Uri option instead, which kcoap-oscore decomposes as RFC 8613
 * §4.1.3.3 says, sending an outer Proxy-Uri with the scheme and authority.
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

package dev.kcoap.transport.ktor.websocket

import dev.kcoap.transport.Endpoint

/**
 * The servers a browser page names for the js tests: `BrowserAgainstJvmListenerTest` (jvmTest) serves
 * the page as `?coap=<ws URI>&plain=<ws URI>`, [coap] being a `KtorWebSocketListener` on the JVM and
 * [plain] a WebSocket endpoint that selects no subprotocol.
 */
internal class BrowserPage(val coap: Endpoint.WebSocket, val plain: Endpoint.WebSocket) {
    companion object {
        /**
         * The servers of the page these tests run in, or null on Node.js.
         *
         * @throws IllegalStateException in a browser page that does not name them.
         */
        fun current(): BrowserPage? {
            if (js("typeof window === 'undefined'") as Boolean) return null
            val query: dynamic = js("new URLSearchParams(window.location.search)")
            fun endpoint(name: String): Endpoint.WebSocket {
                val uri = query.get(name) as String? ?: error("The page names no '$name' server: ${js("window.location.href")}")
                return Endpoint.WebSocket(uri)
            }
            return BrowserPage(endpoint("coap"), endpoint("plain"))
        }
    }
}

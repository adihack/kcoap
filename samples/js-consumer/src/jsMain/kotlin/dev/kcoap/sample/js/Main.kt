package dev.kcoap.sample.js

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import org.w3c.fetch.RequestInit

/**
 * The page's code: asks the server that served the page for a new OSCORE session, reads `lights` with OSCORE over
 * CoAP over WebSockets on the page's own host and port, and shows the answer. It also sends the outcome back to the
 * sample server's `/report`, which is how the headless-browser check in CI learns what the page saw.
 */
fun main() {
    val scope = MainScope()
    scope.launch {
        val outcome = try {
            val session = OscoreSession.decode(post("/oscore-session", ""))
            // RFC 8323 §8.4: coaps+ws when the browser reached the page over TLS, coap+ws otherwise.
            val scheme = if (window.location.protocol == "https:") "coaps+ws" else "coap+ws"
            readLights(scope, "$scheme://${window.location.host}/lights", session)
        } catch (e: Throwable) {
            "failed: $e"
        }
        console.log("kcoap js-consumer: $outcome")
        document.getElementById("lights")?.textContent = outcome
        post("/report", outcome)
    }
}

/** POSTs [body] to [path] on the page's server and returns the response's text. */
private suspend fun post(path: String, body: String): String {
    // A plain object with only these members: the RequestInit(...) factory sets every member it is not given to
    // null, and fetch refuses null for its enumerated members (cache, mode, redirect) with a TypeError.
    val init = js("({})").unsafeCast<RequestInit>()
    init.method = "POST"
    init.body = body
    val response = window.fetch(path, init).await()
    check(response.ok) { "POST $path answered ${response.status}" }
    return response.text().await()
}

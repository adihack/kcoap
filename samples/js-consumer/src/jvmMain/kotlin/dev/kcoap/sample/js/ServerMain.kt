package dev.kcoap.sample.js

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Serves the sample for a browser: `./gradlew -p samples/js-consumer runServer` builds the production bundle and
 * runs this with its directory, then http://127.0.0.1:8080/ shows the page. Arguments: the site directory, and
 * optionally the port.
 */
fun main(args: Array<String>) {
    val site = File(args.firstOrNull() ?: error("Arguments: <site directory> [port]"))
    val port = args.getOrNull(1)?.toInt() ?: 8080
    runBlocking(Dispatchers.IO) {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = SampleServer.start(scope, site, port = port) { outcome -> println("The page reported: $outcome") }
        println("Open http://127.0.0.1:${server.port}/ in a browser; Ctrl-C stops the server.")
        awaitCancellation()
    }
}

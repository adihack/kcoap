package dev.kcoap.sample.jvm

import dev.kcoap.oscore.EphemeralSequenceNumberStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

/**
 * The tour of docs/getting-started.md in one process, over UDP on the loopback interface: a CoAP client against a
 * CoAP server, then an OSCORE exchange with a pre-shared Master Secret, then an EDHOC handshake that gives the
 * client its OSCORE context. Each server listens on a free port of its own. Every step checks its answer, and the
 * program exits with status 1 when one fails, so `./gradlew run` fails with it.
 */
fun main() {
    val failure = runBlocking(Dispatchers.IO) {
        // The endpoints' coroutines run in this scope: the application's, for as long as it serves.
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            withTimeout(60.seconds) { tour(scope) }
            null
        } catch (e: Exception) {
            e
        } finally {
            scope.cancel()
        }
    }
    if (failure != null) {
        System.err.println("kcoap jvm-consumer failed: $failure")
        failure.printStackTrace()
        exitProcess(1)
    }
    println("kcoap jvm-consumer: every step answered as expected")
}

private suspend fun tour(scope: CoroutineScope) {
    // A first client and server.
    val helloPort = freePort()
    val hello = helloServer(scope, helloPort)
    try {
        expect("CoAP GET coap://127.0.0.1:$helloPort/hello", "Hello", helloClient(scope, helloPort))
    } finally {
        hello.close()
    }

    // OSCORE with pre-shared keys. This Master Secret is new in this process run, so the sequence numbers need
    // not outlive it and an ephemeral store is right; a Master Secret that outlives the process needs a durable
    // store (kcoap-oscore's README has recipes).
    val oscorePort = freePort()
    val masterSecret = crypto.randomBytes(16)
    val oscore = oscoreServer(scope, masterSecret, EphemeralSequenceNumberStore(), oscorePort)
    try {
        val context = clientContext(masterSecret, EphemeralSequenceNumberStore())
        expect("OSCORE GET coap://127.0.0.1:$oscorePort/lights", "on", oscoreClient(scope, context, oscorePort))
    } finally {
        oscore.close()
    }

    // EDHOC, then OSCORE.
    val edhocPort = freePort()
    val clientCredential = credential("client", 0x0c)
    val serverCredential = credential("server", 0x05)
    val edhoc = edhocServer(scope, responderConfig(serverCredential, clientCredential), edhocPort)
    try {
        val answer = edhocClient(scope, initiatorConfig(clientCredential, serverCredential), edhocPort)
        expect("EDHOC, then OSCORE GET coap://127.0.0.1:$edhocPort/lights", "on for client", answer)
    } finally {
        edhoc.close()
    }
}

private fun expect(step: String, expected: String, answer: String) {
    println("$step: $answer")
    check(answer == expected) { "$step answered '$answer', not '$expected'" }
}

/** A UDP port free on the loopback interface a moment ago, for the next server. */
private fun freePort(): Int = DatagramSocket(0, InetAddress.getLoopbackAddress()).use { it.localPort }

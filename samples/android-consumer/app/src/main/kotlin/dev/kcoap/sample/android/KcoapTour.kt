package dev.kcoap.sample.android

import dev.kcoap.crypto.CryptoBackend
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * One pass over kcoap's public API as an Android app uses it: the crypto capability report, OSCORE
 * contexts with durable sequence numbers, an EDHOC Initiator configuration, and a plain and an
 * OSCORE-protected CoAP GET over UDP to a server in the same app.
 *
 * The release build shrinks all of it with R8, so whatever kcoap needs at runtime must survive shrinking
 * without rules of the app's own; on a device or emulator (PL-2) the report shows what each API level
 * runs. Nothing here touches Android, so it runs on any JVM too.
 *
 * @param setup names [crypto] in the report; class names do not survive R8.
 * @param dataDirectory where OSCORE keeps its sequence numbers.
 */
class KcoapTour(
    private val crypto: CryptoBackend,
    private val setup: String,
    private val dataDirectory: File,
) {
    /** Runs every step and returns what each found; the endpoints run in [scope] and are closed again. */
    suspend fun run(scope: CoroutineScope): String = buildString {
        appendLine("Crypto: $setup")
        append(capabilityReport(crypto))
        val contexts = oscoreContexts(crypto, dataDirectory)
        appendLine("OSCORE: ${contexts.client} and ${contexts.server}")
        val edhoc = edhocInitiatorConfig(crypto)
        val suites = edhoc.cipherSuites.joinToString { "${it.value}" }
        appendLine("EDHOC Initiator: method ${edhoc.method}, suites $suites")
        append(loopbackGets(scope, contexts))
    }
}

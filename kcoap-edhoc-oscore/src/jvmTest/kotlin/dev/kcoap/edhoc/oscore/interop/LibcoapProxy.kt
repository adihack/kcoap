package dev.kcoap.edhoc.oscore.interop

import dev.kcoap.coap.CoapUri
import dev.kcoap.transport.CoapScheme
import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay

/**
 * libcoap v4.3.5b's `coap-server` as the gateway of the browser topology: a CoAP forward-proxy (RFC 7252 §5.7.2)
 * that browsers reach over WebSockets (RFC 8323 §4, `-w`) and that forwards to devices over UDP. It runs from the
 * `kcoap/libcoap` image (`interop/libcoap/Dockerfile`, built with `--enable-proxy-code`) on the host network as
 *
 * ```
 * coap-server -A 127.0.0.1 -p <port> -w <ws port> -X 1152 -v 7 -P ,kcoap-proxy
 * ```
 *
 * `-P ,kcoap-proxy` makes it a direct forward-proxy (no next hop) known as `kcoap-proxy`: a request with
 * Proxy-Uri, or Proxy-Scheme and a Uri-Host other than that name, is forwarded; anything else it serves itself.
 * `-X 1152` keeps the Max-Message-Size of its CSM within the 1472-byte buffer it reads WebSocket frames into
 * (interop/README.md, "Over TCP and WebSockets").
 *
 * libcoap 4.3.5b's direct proxy never reads the target from the request (docs/adr/0009-browser-topology-interop.md):
 * it opens `coap://localhost:5683` for every request. The container's `/etc/hosts` is therefore replaced by one
 * that maps `localhost` to 127.0.0.1 alone, so the target does not depend on whether the host's `/etc/hosts`
 * also names `::1` localhost, and the device behind the proxy listens on 127.0.0.1:5683, the target the requests
 * name, which a corrected proxy opens as well.
 */
internal class LibcoapProxy private constructor(
    /** The WebSocket port browsers connect to. */
    val webSocketPort: Int,
    private val container: String,
    private val hosts: File,
) : AutoCloseable {
    /** `coap+ws://127.0.0.1:<port>`, the proxy as a browser addresses it. */
    val webSocketUri: CoapUri get() = CoapUri(CoapScheme.COAP_WS, "127.0.0.1", webSocketPort)

    /** Everything the proxy logged so far at verbosity 7; read before [close], which removes its log. */
    fun logs(): String = run(listOf("docker", "logs", container), 30).output

    /** The messages the proxy logged so far, in order. */
    fun messages(): List<ProxiedMessage> = ProxiedMessage.parse(logs())

    /**
     * The messages the proxy logged, once [condition] holds for them or [timeout] passed. libcoap logs a message
     * once it has handled it, so the last lines can come after the client already has its response.
     */
    suspend fun awaitMessages(
        timeout: Duration = 5.seconds,
        condition: (List<ProxiedMessage>) -> Boolean,
    ): List<ProxiedMessage> {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            val messages = messages()
            if (condition(messages) || deadline.hasPassedNow()) return messages
            delay(100.milliseconds)
        }
    }

    override fun close() {
        run(listOf("docker", "rm", "-f", container), 30)
        hosts.delete()
    }

    /**
     * One message as libcoap printed it: `v:WebSocket c:POST {01} [ Uri-Host:127.0.0.1, ... ]` on the
     * browser's side, `v:1 t:CON c:POST i:1a2b {01} [ ... ]` on the device's side. libcoap names the OSCORE option
     * `Oscore` and prints the EDHOC option, which 4.3.5b does not know, by its number, `21`.
     */
    class ProxiedMessage(val line: String) {
        /** Whether it went over the WebSocket connection, the browser's side; otherwise over UDP. */
        val isWebSocket: Boolean get() = line.startsWith("v:WebSocket ")

        /** The code as libcoap prints it: `GET`, `POST`, `2.04`, `CSM`. */
        val code: String get() = Regex(""" c:([\w.]+) """).find(line)?.groupValues?.get(1) ?: ""

        /** The printed options, between the brackets. */
        val options: String get() = line.substringAfter('[', "").substringBefore(']', "")

        /** Whether an option named [name] is printed, as `Name:` with or without a value. */
        fun has(name: String): Boolean = Regex("""(^|[ ,])${Regex.escape(name)}:""").containsMatchIn(options)

        override fun toString(): String = line

        companion object {
            /** The `v:` lines of a libcoap log: one per message sent or received, whatever the direction. */
            fun parse(log: String): List<ProxiedMessage> =
                log.lines().map { it.trimEnd('\r') }.filter { it.startsWith("v:1 ") || it.startsWith("v:WebSocket ") }
                    .map(::ProxiedMessage)
        }
    }

    companion object {
        /** The image to run; `KCOAP_LIBCOAP_IMAGE` selects another libcoap build. */
        val IMAGE: String = System.getenv("KCOAP_LIBCOAP_IMAGE") ?: "kcoap/libcoap:v4.3.5b"

        /** The name the proxy is known by; requests whose Uri-Host is another host are forwarded. */
        const val NAME: String = "kcoap-proxy"

        /** Whether docker works and the image exists; the tests are skipped otherwise. */
        val isAvailable: Boolean by lazy {
            exitCode("docker", "info") == 0 && exitCode("docker", "image", "inspect", IMAGE) == 0
        }

        /** Starts a proxy on free ports; [close] removes its container. */
        fun start(): LibcoapProxy {
            val port = freeUdpAndTcpPort()
            val webSocketPort = ServerSocket(0).use { it.localPort }
            val name = "kcoap-interop-proxy-${UUID.randomUUID()}"
            val hosts = Files.createTempFile("kcoap-proxy", ".hosts").toFile()
            hosts.writeText("127.0.0.1 localhost\n")
            // -t: on a terminal libcoap's log is line-buffered, so `docker logs` has each message once it is handled.
            val result = run(
                listOf(
                    "docker", "run", "-d", "-t", "--rm", "--name", name, "--network", "host",
                    "-v", "${hosts.absolutePath}:/etc/hosts:ro", IMAGE,
                    "coap-server", "-A", "127.0.0.1", "-p", "$port", "-w", "$webSocketPort", "-X", "1152", "-v", "7",
                    "-P", ",$NAME",
                ),
                60,
            )
            if (result.exitCode != 0) {
                hosts.delete()
                error("The libcoap proxy did not start: ${result.output}")
            }
            return LibcoapProxy(webSocketPort, name, hosts)
        }

        /** A port free for both UDP and TCP, since `coap-server -p` listens on both (RFC 8323 §8.1). */
        private fun freeUdpAndTcpPort(): Int {
            while (true) {
                val port = DatagramSocket(0).use { it.localPort }
                if (runCatching { ServerSocket(port).close() }.isSuccess) return port
            }
        }

        private class Result(val exitCode: Int, val output: String)

        private fun exitCode(vararg command: String): Int = try {
            run(command.toList(), 60).exitCode
        } catch (_: Exception) {
            -1
        }

        private fun run(command: List<String>, timeoutSeconds: Long): Result {
            val output = File.createTempFile("kcoap-proxy", ".log")
            try {
                val process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output).start()
                if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    return Result(-1, output.readText() + "\n(timed out)")
                }
                return Result(process.exitValue(), output.readText())
            } finally {
                output.delete()
            }
        }
    }
}

package dev.kcoap.coap.interop

import java.io.File
import java.net.DatagramSocket
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Runs libcoap's `coap-server` and `coap-client` from the `kcoap/libcoap` image (built from
 * `interop/libcoap/Dockerfile`), on the host network so they reach kcoap on 127.0.0.1.
 */
object Libcoap {
    /** The image to run; `KCOAP_LIBCOAP_IMAGE` selects another libcoap build. */
    val IMAGE: String = System.getenv("KCOAP_LIBCOAP_IMAGE") ?: "kcoap/libcoap:v4.3.5b"

    /** Whether docker works and the image exists; interop tests are skipped otherwise. */
    val isAvailable: Boolean by lazy { exitCode("docker", "info") == 0 && exitCode("docker", "image", "inspect", IMAGE) == 0 }

    /** A UDP port nobody listens on right now. */
    fun freePort(): Int = DatagramSocket(0).use { it.localPort }

    /** A port free for both UDP and TCP, since `coap-server -p` listens on both (RFC 8323 §8.1 shares 5683). */
    fun freeUdpAndTcpPort(): Int {
        while (true) {
            val port = freePort()
            val tcpFree = runCatching { ServerSocket(port).close() }.isSuccess
            if (tcpFree) return port
        }
    }

    /** A TCP port nobody listens on right now. */
    fun freeTcpPort(): Int = ServerSocket(0).use { it.localPort }

    class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        /** The messages libcoap logged at verbosity 6 or more: `v:1 t:CON c:GET i:1234 {} [ ... ]`. */
        val messages: List<String> get() = (stdout + "\n" + stderr).lines().filter { it.startsWith("v:1 ") }

        override fun toString(): String = "exit $exitCode\nstdout: $stdout\nstderr: $stderr"
    }

    /**
     * A running `coap-server`, on UDP and TCP at [port] and, if started so, on WebSockets at
     * [webSocketPort]; [close] removes the container.
     */
    class Server(val port: Int, private val container: String, val webSocketPort: Int? = null) : AutoCloseable {
        val uri: String get() = "coap://127.0.0.1:$port"
        val tcpUri: String get() = "coap+tcp://127.0.0.1:$port"
        val webSocketUri: String get() = "coap+ws://127.0.0.1:${checkNotNull(webSocketPort)}"

        fun logs(): String = run(listOf("docker", "logs", container), 30).let { it.stdout + it.stderr }

        override fun close() {
            exitCode("docker", "rm", "-f", container)
        }
    }

    /** Starts `coap-server -p <port>` with [arguments], and `-w <port>` for WebSockets if [webSockets]. */
    fun startServer(vararg arguments: String, webSockets: Boolean = false): Server {
        val port = freeUdpAndTcpPort()
        val webSocketPort = if (webSockets) freeTcpPort() else null
        val name = "kcoap-interop-${UUID.randomUUID()}"
        val webSocketArguments = webSocketPort?.let { listOf("-w", "$it") } ?: emptyList()
        val result = run(
            listOf("docker", "run", "-d", "--rm", "--name", name, "--network", "host", IMAGE, "coap-server", "-p", "$port") +
                webSocketArguments + arguments,
            60,
        )
        check(result.exitCode == 0) { "coap-server did not start: $result" }
        return Server(port, name, webSocketPort)
    }

    /** Runs `coap-client` with [arguments]; [mount] is visible as /data in the container. */
    fun client(vararg arguments: String, mount: File? = null, timeoutSeconds: Long = 60): Result {
        val volume = mount?.let { listOf("-v", "${it.absolutePath}:/data") } ?: emptyList()
        return run(listOf("docker", "run", "--rm", "--network", "host") + volume + listOf(IMAGE, "coap-client") + arguments, timeoutSeconds)
    }

    private fun exitCode(vararg command: String): Int = try {
        run(command.toList(), 60).exitCode
    } catch (e: Exception) {
        -1
    }

    private fun run(command: List<String>, timeoutSeconds: Long): Result {
        val stdout = File.createTempFile("libcoap", ".out")
        val stderr = File.createTempFile("libcoap", ".err")
        try {
            val process = ProcessBuilder(command).redirectOutput(stdout).redirectError(stderr).start()
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result(-1, stdout.readText(), stderr.readText() + "\n(timed out)")
            }
            return Result(process.exitValue(), stdout.readText(), stderr.readText())
        } finally {
            stdout.delete()
            stderr.delete()
        }
    }
}

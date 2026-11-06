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

    /** A port that is free for UDP and TCP, and so is the next one, where `coap-server` puts (D)TLS. */
    fun freePortPair(): Int {
        while (true) {
            val port = freeUdpAndTcpPort()
            if (port == 65535) continue
            val nextFree = runCatching { DatagramSocket(port + 1).close() }.isSuccess &&
                runCatching { ServerSocket(port + 1).close() }.isSuccess
            if (nextFree) return port
        }
    }

    class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        /** The messages libcoap logged at verbosity 6 or more: `v:1 t:CON c:GET i:1234 {} [ ... ]`. */
        val messages: List<String> get() = (stdout + "\n" + stderr).lines().filter { it.startsWith("v:1 ") }

        /** The messages libcoap sent and received, in order, from its log at verbosity 7 ([wire]). */
        val wire: List<Wire> get() = wire(stdout + "\n" + stderr)

        override fun toString(): String = "exit $exitCode\nstdout: $stdout\nstderr: $stderr"
    }

    /**
     * One message on libcoap's side of the wire: [isSent] by libcoap or received, and the line libcoap printed
     * for it, such as `v:1 t:NON c:GET i:8176 {} [ Uri-Path:big, Q-Block2:0/M/64, Request-Tag:0x9959f003 ]`.
     */
    class Wire(val isSent: Boolean, val line: String) {
        /** The message type: `CON`, `NON`, `ACK` or `RST`; null over reliable transports, which have none. */
        val type: String? get() = Regex(""" t:(\w+) """).find(line)?.groupValues?.get(1)

        /** The code as libcoap prints it: `GET`, `2.05`, `4.08`. */
        val code: String? get() = Regex(""" c:([\w.]+) """).find(line)?.groupValues?.get(1)

        /** The token in hex, empty for none. */
        val token: String? get() = Regex("""\{(\w*)\}""").find(line)?.groupValues?.get(1)

        /** Every value of [option], as printed: `Q-Block2` gives `0/M/64`. */
        fun option(option: String): List<String> = Regex("""[\[ ]$option:([^,\]]+?)(?:,| \])""").findAll(line).map { it.groupValues[1].trim() }.toList()

        /** The block number of the Q-Block1 or Q-Block2 option [option] (the first one). */
        fun number(option: String): Long? = option(option).firstOrNull()?.substringBefore('/')?.toLong()

        override fun toString(): String = (if (isSent) "> " else "< ") + line
    }

    /**
     * The messages in a libcoap log at verbosity 7: each `v:1`, `v:Reliable` or `v:WebSocket` line right after
     * a `netif: sent` or `netif: recv` line, which says its direction.
     */
    fun wire(log: String): List<Wire> {
        val wire = mutableListOf<Wire>()
        var direction: Boolean? = null
        for (line in log.lines()) {
            when {
                "netif: sent" in line -> direction = true
                "netif: recv" in line -> direction = false
                direction != null && (line.startsWith("v:1 ") || line.startsWith("v:Reliable ") || line.startsWith("v:WebSocket ")) -> {
                    wire += Wire(direction, line)
                    direction = null
                }
            }
        }
        return wire
    }

    /**
     * A running `coap-server`, on UDP and TCP at [port] and, if started so, on WebSockets at
     * [webSocketPort]; [close] removes the container.
     */
    class Server(val port: Int, private val container: String, val webSocketPort: Int? = null) : AutoCloseable {
        val uri: String get() = "coap://127.0.0.1:$port"
        val tcpUri: String get() = "coap+tcp://127.0.0.1:$port"
        val webSocketUri: String get() = "coap+ws://127.0.0.1:${checkNotNull(webSocketPort)}"

        /** Where a server from [startTlsServer] serves `coaps+tcp`: the port after [port]. */
        val tlsPort: Int get() = port + 1

        fun logs(): String = run(listOf("docker", "logs", container), 30).let { it.stdout + it.stderr }

        /** The text of the file at [path] in the server's container. */
        fun read(path: String): String {
            val result = run(listOf("docker", "exec", container, "cat", path), 30)
            check(result.exitCode == 0) { "Cannot read $path: $result" }
            return result.stdout
        }

        /** The messages the server sent and received so far; read before [close], which removes its log. */
        fun wire(): List<Wire> = wire(logs())

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

    /**
     * Starts `coap-server -p <port>` with TLS: a throwaway certificate authority and a server certificate for
     * [subjectAltName] (as openssl writes it, `DNS:localhost`), both EC P-256 and valid for a day, are made by openssl
     * in the container before the server starts. The server then serves `coaps+tcp` on `port + 1` and, if
     * [secureWebSockets], `coaps+ws` on [Server.webSocketPort]. [Server.read] fetches `/certs/ca.pem` and
     * `/certs/server.pem`; the keys never leave the container.
     */
    fun startTlsServer(subjectAltName: String, vararg arguments: String, secureWebSockets: Boolean = false): Server {
        val port = freePortPair()
        val webSocketPort = if (secureWebSockets) freeTcpPort() else null
        val name = "kcoap-interop-${UUID.randomUUID()}"
        val script = """
            set -e
            mkdir /certs && cd /certs
            openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out ca.key 2>/dev/null
            openssl req -x509 -new -key ca.key -sha256 -days 1 -subj '/CN=kcoap interop CA' \
                -addext basicConstraints=critical,CA:TRUE -addext keyUsage=critical,keyCertSign,cRLSign -out ca.pem
            openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out server.key 2>/dev/null
            openssl req -new -key server.key -subj '/CN=libcoap interop server' -out server.csr
            printf 'basicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=serverAuth\nsubjectAltName=%s\n' "${'$'}SAN" > server.ext
            openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -days 1 -sha256 \
                -extfile server.ext -out server.pem 2>/dev/null
            exec coap-server "${'$'}@"
        """.trimIndent()
        val webSocketArguments = webSocketPort?.let { listOf("-w", ",$it") } ?: emptyList()
        val result = run(
            listOf("docker", "run", "-d", "--rm", "--name", name, "--network", "host", "-e", "SAN=$subjectAltName", IMAGE) +
                listOf("sh", "-c", script, "coap-server", "-p", "$port", "-c", "/certs/server.pem", "-j", "/certs/server.key") +
                webSocketArguments + arguments,
            60,
        )
        check(result.exitCode == 0) { "coap-server did not start: $result" }
        return Server(port, name, webSocketPort)
    }

    /**
     * Runs `coap-client` with [arguments]; [mount] is visible as /data in the container. libcoap 4.3.5 reads
     * `-L` only before `-P` and `-f`, so the block mode goes first.
     */
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

package dev.kcoap.edhoc

import dev.kcoap.common.hexToBytes
import dev.kcoap.common.toHex
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.fail

/**
 * One libedhoc EDHOC session in a container: `interop/libedhoc/peer/edhoc_peer.c` driven over its line
 * protocol on stdin and stdout. Every line the peer writes is kept for diagnostics, and libedhoc's own log
 * (stderr) goes to `build/interop-logs/` when a test fails.
 */
internal class LibedhocPeer private constructor(
    private val name: String,
    private val process: Process,
) : AutoCloseable {
    private val input = process.outputStream.bufferedWriter()
    private val lines = LinkedBlockingQueue<String>()
    private val transcript = StringBuffer()
    private val stderr = StringBuffer()

    init {
        thread(isDaemon = true, name = "$name-stdout") {
            process.inputStream.bufferedReader().forEachLine { line ->
                transcript.append("< ").append(line).append('\n')
                lines.put(line)
            }
            lines.put(END)
        }
        thread(isDaemon = true, name = "$name-stderr") {
            process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
        }
    }

    /** One line of the peer's output: its kind (`msg`, `result`, `done`, ...) and the remaining tokens. */
    class Line(val kind: String, val tokens: List<String>) {
        override fun toString(): String = (listOf(kind) + tokens).joinToString(" ")
    }

    fun send(message: ByteArray) {
        transcript.append("> msg ").append(message.toHex()).append('\n')
        input.write("msg ${message.toHex().ifEmpty { "-" }}\n")
        input.flush()
    }

    /** The next line of output; fails when the peer exits or stays silent for [timeoutSeconds]. */
    fun next(timeoutSeconds: Long = 60): Line {
        val line = lines.poll(timeoutSeconds, TimeUnit.SECONDS) ?: fail("libedhoc peer timed out\n${diagnostics()}")
        if (line == END) fail("libedhoc peer exited\n${diagnostics()}")
        val tokens = line.split(' ')
        return Line(tokens[0], tokens.drop(1))
    }

    /** The next line, which must be of [kind]. */
    fun expect(kind: String): Line {
        val line = next()
        if (line.kind != kind) fail("Expected '$kind' from libedhoc, got '$line'\n${diagnostics()}")
        return line
    }

    /** The next message the peer sends: an EDHOC message or an error message. */
    fun receiveMessage(): ByteArray = bytes(expect("msg").tokens.single())

    /** The results the peer prints after a completed session, up to `done`. */
    fun results(): Map<String, ByteArray> {
        val results = mutableMapOf<String, ByteArray>()
        while (true) {
            val line = next()
            when (line.kind) {
                "result" -> results[line.tokens[0]] = if (line.tokens[0] == "suite") {
                    byteArrayOf(line.tokens[1].toInt().toByte())
                } else {
                    bytes(line.tokens[1])
                }
                "done" -> return results
                else -> fail("Unexpected '$line' while reading results\n${diagnostics()}")
            }
        }
    }

    /** Waits for the process to end and returns its exit status. */
    fun exitStatus(): Int {
        if (!process.waitFor(60, TimeUnit.SECONDS)) fail("libedhoc peer did not exit\n${diagnostics()}")
        return process.exitValue()
    }

    /** What the peer said and logged, for failure messages. */
    fun diagnostics(): String = "--- protocol ($name)\n$transcript--- libedhoc stderr\n$stderr"

    /** Keeps libedhoc's log next to the test results. */
    fun saveLog(test: String) {
        val dir = File("build/interop-logs").apply { mkdirs() }
        File(dir, "$test-$name.log").writeText(diagnostics())
    }

    override fun close() {
        input.runCatching { close() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        const val IMAGE = "kcoap/libedhoc:0363bd3"
        private const val END = "\u0000end"

        private fun bytes(hex: String): ByteArray = if (hex == "-") ByteArray(0) else hex.hexToBytes()

        /** Starts `edhoc_peer` with [arguments] in a fresh container. */
        fun start(name: String, arguments: List<String>): LibedhocPeer {
            val command = listOf("docker", "run", "-i", "--rm", IMAGE, "edhoc_peer") + arguments
            return LibedhocPeer(name, ProcessBuilder(command).start())
        }

        private val isAvailable: Boolean by lazy {
            try {
                val check = ProcessBuilder("docker", "run", "--rm", IMAGE, "test", "-x", "/usr/local/bin/edhoc_peer")
                    .redirectErrorStream(true)
                    .start()
                check.waitFor(120, TimeUnit.SECONDS) && check.exitValue() == 0
            } catch (_: Exception) {
                false
            }
        }

        /** Skips the calling test unless Docker and an image with `edhoc_peer` are present. */
        fun assumeAvailable() {
            assumeTrue(isAvailable) {
                "Docker with $IMAGE (built from interop/libedhoc/Dockerfile) is needed for libedhoc interop"
            }
        }
    }
}

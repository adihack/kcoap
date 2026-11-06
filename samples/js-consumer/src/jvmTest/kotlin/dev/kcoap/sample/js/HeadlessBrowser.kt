package dev.kcoap.sample.js

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A browser in headless mode showing one page, with a profile of its own under a work directory, for
 * [BrowserSmokeTest]: Firefox when the command's file name says so, otherwise Chrome or Chromium. [close] ends it
 * and every process it started. The same approach as kcoap's own browser test (kcoap-transport-ktor-websocket).
 */
internal class HeadlessBrowser private constructor(
    private val process: Process,
    private val profile: File,
    private val log: File,
) : AutoCloseable {
    /** The last lines the browser printed, for a failure message. */
    fun logTail(lines: Int = 40): String = if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else ""

    /** Ends the browser, its descendants, and whatever else runs on its profile. */
    override fun close() {
        val onProfile = ProcessHandle.allProcesses().filter { handle ->
            handle.info().commandLine().map { profile.path in it }.orElse(false)
        }.toList()
        val processes = (listOf(process.toHandle()) + process.descendants().toList() + onProfile).distinct()
        processes.forEach { it.destroy() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        processes.forEach { it.destroyForcibly() }
    }

    companion object {
        /**
         * Opens [url] in [command]. Chrome runs without its sandbox, which needs user namespaces that CI runners
         * restrict; the page is the sample's own, served from the loopback interface.
         */
        fun open(command: String, url: String, workDirectory: File): HeadlessBrowser {
            val profile = File(workDirectory, "profile").apply {
                deleteRecursively()
                mkdirs()
            }
            val arguments = if ("firefox" in File(command).name) {
                listOf(command, "--headless", "--new-instance", "--profile", profile.path, url)
            } else {
                listOf(
                    command, "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-dev-shm-usage",
                    "--no-first-run", "--no-default-browser-check", "--user-data-dir=${profile.path}", url,
                )
            }
            val log = File(workDirectory, "browser.log")
            val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log).start()
            return HeadlessBrowser(process, profile, log)
        }
    }
}

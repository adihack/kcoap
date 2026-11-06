package dev.kcoap.transport.ktor.websocket

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A browser in headless mode showing one page, with a profile of its own under a work directory, for
 * [BrowserAgainstJvmListenerTest]. [close] ends it and every process it started.
 */
internal class HeadlessBrowser private constructor(
    private val process: Process,
    private val profile: File,
    private val log: File,
) : AutoCloseable {
    /** The last lines the browser printed, for a failure message. */
    fun logTail(lines: Int = 40): String = if (log.exists()) log.readLines().takeLast(lines).joinToString("\n") else ""

    /**
     * Ends the browser: the process started, its descendants, and whatever else runs on this profile,
     * since Firefox may restart itself on a new profile outside the started process's tree.
     */
    override fun close() {
        val processes = (listOf(process.toHandle()) + process.descendants().toList() + onProfile()).distinct()
        processes.forEach { it.destroy() }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
        processes.forEach { it.destroyForcibly() }
    }

    private fun onProfile(): List<ProcessHandle> = ProcessHandle.allProcesses().filter { handle ->
        handle.info().commandLine().map { profile.path in it }.orElse(false)
    }.toList()

    companion object {
        /**
         * Opens [url] in [command]: Firefox when the command's file name says so, otherwise Chrome or
         * Chromium. Chrome runs without its sandbox, which needs user namespaces that CI runners restrict;
         * the page is the tests' own, served from the loopback interface. Firefox's profile lets the page
         * close its window, so that Firefox quits by itself even where it cannot be signalled (as a snap).
         */
        fun open(command: String, url: String, workDirectory: File): HeadlessBrowser {
            val profile = File(workDirectory, "profile").apply {
                deleteRecursively()
                mkdirs()
            }
            val arguments = if ("firefox" in File(command).name) {
                File(profile, "user.js").writeText(FIREFOX_PREFERENCES.joinToString("\n", postfix = "\n"))
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

        /** A test profile: the page may close its window, and nothing is fetched beyond the page. */
        private val FIREFOX_PREFERENCES = listOf(
            "dom.allow_scripts_to_close_windows" to "true",
            "browser.shell.checkDefaultBrowser" to "false",
            "browser.startup.homepage_override.mstone" to "\"ignore\"",
            "datareporting.policy.dataSubmissionEnabled" to "false",
            "datareporting.healthreport.uploadEnabled" to "false",
            "toolkit.telemetry.reportingpolicy.firstRun" to "false",
            "app.normandy.enabled" to "false",
            "app.update.auto" to "false",
            "extensions.update.enabled" to "false",
            "browser.safebrowsing.malware.enabled" to "false",
            "browser.safebrowsing.phishing.enabled" to "false",
            "browser.safebrowsing.downloads.enabled" to "false",
            "network.captive-portal-service.enabled" to "false",
            "network.connectivity-service.enabled" to "false",
        ).map { (name, value) -> "user_pref(\"$name\", $value);" }
    }
}

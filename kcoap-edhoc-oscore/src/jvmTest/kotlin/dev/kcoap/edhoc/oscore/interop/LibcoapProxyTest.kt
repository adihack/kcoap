package dev.kcoap.edhoc.oscore.interop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * [LibcoapProxy] leaves no container running (WP-I1): not when its log cannot be kept, and not when nothing removes
 * it, as when the test JVM is killed. Skipped when docker or the image is missing.
 */
class LibcoapProxyTest {
    @Test
    fun containerIsRemovedEvenWhenItsLogCannotBeKept() {
        assumeProxy()
        val proxy = LibcoapProxy.start(UdpDevice.loopbackAlias())
        // A regular file as the log's directory, so the log cannot be written.
        val notADirectory = File.createTempFile("kcoap-proxy-log", ".file")
        try {
            assertTrue(proxy.isRunning())
            assertFails { proxy.closeKeepingLog(notADirectory.resolve("proxy.log")) }
            assertFalse(proxy.isRunning(), "the container went although the log failed")
        } finally {
            notADirectory.delete()
            proxy.close()
        }
    }

    @Test
    fun containerStopsByItselfAfterItsLifetime() = runBlocking {
        assumeProxy()
        val proxy = LibcoapProxy.start(UdpDevice.loopbackAlias(), lifetime = 2.seconds)
        try {
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            while (proxy.isRunning() && !deadline.hasPassedNow()) delay(250.milliseconds)
            assertFalse(proxy.isRunning(), "the container outlived its lifetime")
        } finally {
            proxy.close()
        }
    }

    private fun assumeProxy() {
        assumeTrue(LibcoapProxy.isAvailable, "Docker with ${LibcoapProxy.IMAGE} is needed for this interop test")
    }
}

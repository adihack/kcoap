import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * kcoap.core keeps a core module's main code in commonMain (docs/target-architecture.md §1, principle 1),
 * and an adapter, which does not apply it, may have platform main code.
 */
class CorePluginTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun aCoreModuleWithAJvmMainFileFailsVerifyCommonOnly() {
        val core = Fixture(directory, "kcoap.kmp-library", "kcoap.core")
            .file("src/jvmMain/kotlin/dev/kcoap/fixture/Platform.kt", "package dev.kcoap.fixture\n\npublic fun platform(): Int = 2")

        val result = core.runner("verifyCommonOnly").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":verifyCommonOnly")?.outcome)
        assertContains(result.output, "fixture is a core module (kcoap.core)")
        assertContains(result.output, "src/jvmMain/kotlin/dev/kcoap/fixture/Platform.kt")
    }

    @Test
    fun aCoreModuleFailsForMainCodeOfATargetItDoesNotDeclare() {
        val core = Fixture(directory, "kcoap.kmp-library", "kcoap.core")
            .file("src/androidMain/kotlin/dev/kcoap/fixture/Android.kt", "package dev.kcoap.fixture")

        val result = core.runner("verifyCommonOnly").buildAndFail()

        assertContains(result.output, "src/androidMain/kotlin/dev/kcoap/fixture/Android.kt")
    }

    @Test
    fun aCoreModuleWithPlatformTestsPassesAndCheckRunsTheVerification() {
        val core = Fixture(directory, "kcoap.kmp-library", "kcoap.core")
            .file("src/jvmTest/kotlin/dev/kcoap/fixture/DifferentialTest.kt", "package dev.kcoap.fixture")
            // An empty platform source directory is no code.
            .also { directory.resolve("src/appleMain/kotlin").mkdirs() }

        val result = core.runner("verifyCommonOnly", "checkDependencies").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyCommonOnly")?.outcome)
        assertContains(checkDependencies(result.output), "verifyCommonOnly")
    }

    @Test
    fun anAdapterModuleWithJvmMainCodePasses() {
        val adapter = Fixture(directory, "kcoap.kmp-library")
            .file("src/jvmMain/kotlin/dev/kcoap/fixture/Platform.kt", "package dev.kcoap.fixture\n\npublic fun platform(): Int = 2")

        val result = adapter.runner("checkDependencies", "tasks", "--all").build()

        assertContains(checkDependencies(result.output), "allTests")
        assertFalse(result.output.contains("verifyCommonOnly"), result.output)
    }

    /** The line the fixture's checkDependencies task prints. */
    private fun checkDependencies(output: String): String = output.lines().single { it.startsWith("check depends on: ") }
}

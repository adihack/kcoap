import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

/**
 * Every test task of a kcoap module, the JVM's and those of the native, JS and Wasm targets, logs a failed test in
 * full: its assertion message, its cause and its stack, so a CI log says why a test failed.
 */
class TestLoggingTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun everyTestTaskLogsFailuresInFull() {
        val module = Fixture(directory, "kcoap.kmp-library").buildScript(
            """
            tasks.register("testLogging") {
                val lines = project.tasks.withType<AbstractTestTask>().map { task ->
                    val logging = task.testLogging
                    listOf(task.name, logging.exceptionFormat, logging.showExceptions, logging.showCauses, logging.showStackTraces)
                        .joinToString(" ")
                }.sorted()
                doLast { lines.forEach { println("test logging: " + it) } }
            }
            """,
        )

        val result = module.runner("testLogging").build()

        val lines = result.output.lines().filter { it.startsWith("test logging: ") }
        for (task in listOf("jvmTest", "linuxX64Test", "jsNodeTest", "wasmJsNodeTest")) {
            assertContains(lines, "test logging: $task FULL true true true", lines.joinToString("\n"))
        }
        assertTrue(lines.all { it.endsWith(" FULL true true true") }, lines.joinToString("\n"))
    }
}

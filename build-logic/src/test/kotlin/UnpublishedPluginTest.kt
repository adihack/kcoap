import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse

/**
 * The tier plugins publish a module, with ABI validation of its public API, unless kcoap.unpublished is
 * applied before them.
 */
class UnpublishedPluginTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun aTierPluginPublishesTheModuleAndValidatesItsAbi() {
        val published = Fixture(directory, "kcoap.kmp-library")

        val tasks = published.runner("tasks", "--all").build().output

        assertContains(tasks, "publishToMavenLocal")
        assertContains(tasks, "checkKotlinAbi")
        assertContains(tasks, "updateKotlinAbi")
    }

    @Test
    fun anUnpublishedModuleHasNoPublicationAndNoAbiDumps() {
        val unpublished = Fixture(directory, "kcoap.unpublished", "kcoap.kmp-library", "kcoap.core")

        val tasks = unpublished.runner("tasks", "--all").build().output

        assertContains(tasks, "verifyCommonOnly")
        assertFalse(tasks.contains("publishToMavenLocal"), tasks)
        assertFalse(tasks.contains("KotlinAbi"), tasks)
    }

    @Test
    fun kcoapUnpublishedAfterTheTierPluginFailsTheBuild() {
        val misordered = Fixture(directory, "kcoap.kmp-library", "kcoap.unpublished")

        val result = misordered.runner("help").buildAndFail()

        assertContains(result.output, "applies kcoap.unpublished after its kcoap.kmp-* plugin")
    }
}

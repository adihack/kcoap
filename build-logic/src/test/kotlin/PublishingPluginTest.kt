import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * kcoap.publishing publishes a module as `<kcoap.group>:<module>:<kcoap.version>`, the properties in
 * gradle.properties, and the release workflow overrides the version with `-Pkcoap.version`. Every module
 * was once published as `kcoap:<module>:unspecified`, because the coordinates were read from the project
 * before the tier plugin had set its group and version.
 */
class PublishingPluginTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun aPublishedModuleTakesItsCoordinatesFromKcoapGroupAndKcoapVersion() {
        val publications = publications()

        assertEquals("dev.kcoap.fixture:fixture:1.0.0", publications.getValue("kotlinMultiplatform"))
        assertEquals("dev.kcoap.fixture:fixture-jvm:1.0.0", publications.getValue("jvm"))
        assertTrue(publications.values.all { it.startsWith("dev.kcoap.fixture:fixture") && it.endsWith(":1.0.0") }, "$publications")
        assertTrue(publications.values.none { "unspecified" in it }, "$publications")
    }

    @Test
    fun theReleaseWorkflowsKcoapVersionPropertyOverridesTheVersion() {
        val publications = publications("-Pkcoap.version=2.0.0")

        assertEquals("dev.kcoap.fixture:fixture:2.0.0", publications.getValue("kotlinMultiplatform"))
        assertTrue(publications.values.all { it.endsWith(":2.0.0") }, "$publications")
    }

    /**
     * The fixture's Maven publications by name, as `groupId:artifactId:version`. Listing them configures
     * the build only: nothing is compiled or resolved.
     */
    private fun publications(vararg properties: String): Map<String, String> {
        val fixture = Fixture(directory, "kcoap.kmp-library").buildScript(
            """
            val publishing = extensions.getByType<org.gradle.api.publish.PublishingExtension>()
            tasks.register("publications") {
                doLast {
                    publishing.publications.withType<org.gradle.api.publish.maven.MavenPublication>().forEach {
                        println("publication ${'$'}{it.name}=${'$'}{it.groupId}:${'$'}{it.artifactId}:${'$'}{it.version}")
                    }
                }
            }
            """,
        )
        val output = fixture.runner("publications", *properties).build().output
        return output.lines()
            .filter { it.startsWith("publication ") }
            .associate { it.removePrefix("publication ").substringBefore('=') to it.substringAfter('=') }
            .also { assertTrue(it.isNotEmpty(), output) }
    }
}

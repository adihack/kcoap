import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * kcoap.publishing publishes a module as `<kcoap.group>:<module>:<kcoap.version>`, the properties in
 * gradle.properties, and the release workflow overrides the version with `-Pkcoap.version`. Every module
 * was once published as `kcoap:<module>:unspecified`, because the coordinates were read from the project
 * before the tier plugin had set its group and version.
 *
 * Every POM names the license, the website, the maintainer, the issue tracker and the repository, which
 * Maven Central requires (the license, the developer and the SCM) or users look for.
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

    @Test
    fun everyPomNamesTheLicenseTheWebsiteTheMaintainerAndTheIssueTracker() {
        val fixture = Fixture(directory, "kcoap.kmp-library")

        fixture.runner("generatePomFileForKotlinMultiplatformPublication", "generatePomFileForJvmPublication").build()

        for (publication in listOf("kotlinMultiplatform", "jvm")) {
            val file = directory.resolve("build/publications/$publication/pom-default.xml")
            val pom = parse(file)
            assertEquals("https://kcoap.dev", pom.text("/project/url"), publication)
            assertEquals("1", pom.text("count(/project/licenses/license)"), publication)
            assertEquals("Apache-2.0", pom.text("/project/licenses/license/name"), publication)
            assertEquals("https://www.apache.org/licenses/LICENSE-2.0.txt", pom.text("/project/licenses/license/url"), publication)
            assertEquals("repo", pom.text("/project/licenses/license/distribution"), publication)
            assertEquals("1", pom.text("count(/project/developers/developer)"), publication)
            assertEquals("adihack", pom.text("/project/developers/developer/id"), publication)
            assertEquals("Adrian Sołtys", pom.text("/project/developers/developer/name"), publication)
            assertEquals("adrian.soltys@seelso.net", pom.text("/project/developers/developer/email"), publication)
            assertEquals("https://github.com/adihack", pom.text("/project/developers/developer/url"), publication)
            assertEquals("maintainer", pom.text("/project/developers/developer/roles/role"), publication)
            assertEquals("GitHub Issues", pom.text("/project/issueManagement/system"), publication)
            assertEquals("https://github.com/adihack/kcoap/issues", pom.text("/project/issueManagement/url"), publication)
            assertEquals("https://github.com/adihack/kcoap", pom.text("/project/scm/url"), publication)
            // The maintainer's name keeps its "ł": the POM is UTF-8 and says so.
            val bytes = file.readBytes()
            assertContains(String(bytes, Charsets.US_ASCII).lineSequence().first(), "encoding=\"UTF-8\"")
            assertTrue(bytes.containsSequence("Adrian Sołtys".toByteArray(Charsets.UTF_8)), publication)
        }
    }

    @Test
    fun aModuleWithoutALicenseFailsInsteadOfPublishingWithoutOne() {
        val fixture = Fixture(directory, "kcoap.kmp-library")
        val properties = directory.resolve("gradle.properties")
        properties.writeText(properties.readLines().filterNot { it.startsWith("kcoap.license.") }.joinToString("\n"))

        val result = fixture.runner("help").buildAndFail()

        assertContains(result.output, "kcoap.license.name is not set")
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

    private fun parse(file: File): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun Document.text(xpath: String): String = XPathFactory.newInstance().newXPath().evaluate(xpath, this)

    private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
        (0..size - sequence.size).any { start -> sequence.indices.all { this[start + it] == sequence[it] } }
}

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * kcoap.bom makes kcoap-bom: a Java platform whose constraints are the multiplatform and JVM artifacts of
 * every module the build publishes, generated from the modules that apply kcoap.publishing rather than typed
 * by hand, and published with the same coordinates and POM as the modules. `verifyBomConstraints`, part of
 * `check`, compares what the BOM publishes with the publications of the build.
 */
class BomPluginTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun theBomConstrainsTheMultiplatformAndJvmArtifactOfEveryPublishedModuleAndNothingElse() {
        val build = kcoapLikeBuild()

        build.runner(":bom:generatePomFileForMavenPublication", ":bom:generateMetadataFileForMavenPublication").build()

        val published = setOf(
            "dev.kcoap.fixture:universal:1.0.0",
            "dev.kcoap.fixture:universal-jvm:1.0.0",
            "dev.kcoap.fixture:jvmonly:1.0.0",
            "dev.kcoap.fixture:jvmonly-jvm:1.0.0",
        )
        val pom = parse(directory.resolve("bom/build/publications/maven/pom-default.xml"))
        assertEquals(published, pom.constraints().toSet())
        assertEquals(published.size, pom.constraints().size)
        // Published like a module: its own coordinates and kcoap's POM.
        assertEquals("dev.kcoap.fixture", pom.text("/project/groupId"))
        assertEquals("bom", pom.text("/project/artifactId"))
        assertEquals("1.0.0", pom.text("/project/version"))
        assertEquals("pom", pom.text("/project/packaging"))
        assertEquals("Apache-2.0", pom.text("/project/licenses/license/name"))
        assertEquals("https://github.com/adihack/kcoap", pom.text("/project/scm/url"))
        val metadata = directory.resolve("bom/build/publications/maven/module.json").readText().filterNot { it.isWhitespace() }
        for (artifact in published) {
            val (group, module, version) = artifact.split(':')
            assertContains(metadata, "\"group\":\"$group\",\"module\":\"$module\",\"version\":{\"requires\":\"$version\"")
        }
        assertFalse(metadata.contains("\"module\":\"unpublished"), metadata)
    }

    @Test
    fun verifyBomConstraintsIsPartOfCheckAndPassesForTheReleaseVersion() {
        val build = kcoapLikeBuild()

        val dryRun = build.runner(":bom:check", "--dry-run").build().output
        val result = build.runner(":bom:verifyBomConstraints", "-Pkcoap.version=2.0.0").build()

        assertContains(dryRun, ":bom:verifyBomConstraints SKIPPED")
        assertEquals(TaskOutcome.SUCCESS, result.task(":bom:verifyBomConstraints")?.outcome)
        assertEquals(
            listOf(
                "dev.kcoap.fixture:jvmonly-jvm:2.0.0",
                "dev.kcoap.fixture:jvmonly:2.0.0",
                "dev.kcoap.fixture:universal-jvm:2.0.0",
                "dev.kcoap.fixture:universal:2.0.0",
            ),
            directory.resolve("bom/build/bom/constraints.txt").readLines(),
        )
    }

    @Test
    fun aMissingOrAStrayConstraintFailsVerifyBomConstraints() {
        val build = kcoapLikeBuild().file(
            "bom/build.gradle.kts",
            """
            plugins { id("kcoap.bom") }
            // A constraint on a module that is not published, and one published module's JVM artifact dropped.
            dependencies { constraints { api("dev.kcoap.fixture:unpublished:1.0.0") } }
            gradle.projectsEvaluated {
                val constraints = configurations["api"].dependencyConstraints
                constraints.matching { it.name == "jvmonly-jvm" }.toList().forEach { constraints.remove(it) }
            }
            """,
        )

        val result = build.runner(":bom:verifyBomConstraints").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":bom:verifyBomConstraints")?.outcome)
        assertContains(result.output, "dev.kcoap.fixture:bom:1.0.0 does not constrain exactly the published modules")
        assertContains(result.output, "POM lacks dev.kcoap.fixture:jvmonly-jvm:1.0.0")
        assertContains(result.output, "POM constrains dev.kcoap.fixture:unpublished:1.0.0, which is not published")
        assertContains(result.output, "module metadata (apiElements) lacks dev.kcoap.fixture:jvmonly-jvm:1.0.0")
        assertContains(result.output, "module metadata (runtimeElements) constrains dev.kcoap.fixture:unpublished:1.0.0")
    }

    @Test
    fun aVariantWithoutConstraintsFailsVerifyBomConstraints() {
        val build = kcoapLikeBuild().file(
            "bom/build.gradle.kts",
            """
            plugins { id("kcoap.bom") }
            // Every constraint moved to the runtime configuration: runtimeElements and the POM keep them, but
            // apiElements, which a compile classpath selects, then has no dependencyConstraints at all.
            gradle.projectsEvaluated {
                val api = configurations["api"].dependencyConstraints
                val moved = api.toList()
                api.clear()
                moved.forEach { dependencies.constraints.add("runtime", it.group + ":" + it.name + ":" + it.version) }
            }
            """,
        )

        val result = build.runner(":bom:verifyBomConstraints").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":bom:verifyBomConstraints")?.outcome)
        assertContains(result.output, "module metadata (apiElements) lacks dev.kcoap.fixture:universal:1.0.0")
        assertContains(result.output, "module metadata (apiElements) lacks dev.kcoap.fixture:jvmonly-jvm:1.0.0")
        assertFalse(result.output.contains("POM lacks"), result.output)
        assertFalse(result.output.contains("module metadata (runtimeElements) lacks"), result.output)
    }

    @Test
    fun aKcoapModuleThatArrivesAtAHigherVersionLiftsTheOtherModulesThroughTheBom() {
        // Two published JVM modules and the BOM, published at 1.0.0 and at 2.0.0 into a file repository.
        val repository = directory.resolve("repo")
        val producer = Fixture(directory.resolve("producer"), "base")
            .include(":alpha", ":beta", ":bom")
            .file("alpha/build.gradle.kts", """plugins { id("kcoap.kmp-jvm-library") }""")
            .file("beta/build.gradle.kts", """plugins { id("kcoap.kmp-jvm-library") }""")
            .file("bom/build.gradle.kts", """plugins { id("kcoap.bom") }""")
            .buildScript(
                """
                subprojects {
                    pluginManager.withPlugin("maven-publish") {
                        extensions.configure<PublishingExtension> {
                            repositories { maven { name = "fixture"; url = uri("${repository.toURI()}") } }
                        }
                    }
                }
                """,
            )
        for (version in listOf("1.0.0", "2.0.0")) {
            producer.runner("publishAllPublicationsToFixtureRepository", "-Pkcoap.version=$version").build()
        }
        // A library built against beta 2.0.0, and an application that writes kcoap's version once, through the
        // BOM at 1.0.0, and also uses that library.
        repository.resolve("com/example/sdk/1.0/sdk-1.0.pom").apply { parentFile.mkdirs() }.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>com.example</groupId>
              <artifactId>sdk</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>dev.kcoap.fixture</groupId>
                  <artifactId>beta</artifactId>
                  <version>2.0.0</version>
                </dependency>
              </dependencies>
            </project>
            """.trimIndent(),
        )
        val application = Fixture(directory.resolve("application"), "java-library").buildScript(
            """
            repositories {
                maven { url = uri("${repository.toURI()}") }
                mavenCentral()
            }
            dependencies {
                implementation(platform("dev.kcoap.fixture:bom:1.0.0"))
                implementation("dev.kcoap.fixture:alpha")
                implementation("com.example:sdk:1.0")
            }
            tasks.register("kcoapVersions") {
                val versions = configurations.named("runtimeClasspath").map { classpath ->
                    classpath.incoming.resolutionResult.allComponents.mapNotNull { it.moduleVersion }
                        .filter { it.group == "dev.kcoap.fixture" }.map { it.name + ":" + it.version }.sorted()
                }
                doLast { println("kcoap versions: " + versions.get().joinToString()) }
            }
            """,
        )

        val output = application.runner("kcoapVersions").build().output

        // beta 2.0.0 brings kcoap-bom 2.0.0, which wins over 1.0.0 and lifts alpha to 2.0.0 as well.
        assertContains(output, "kcoap versions: alpha-jvm:2.0.0, alpha:2.0.0, beta-jvm:2.0.0, beta:2.0.0, bom:2.0.0")
        // Maven reads the POM, where the BOM is an import in dependencyManagement at the module's version.
        val pom = parse(repository.resolve("dev/kcoap/fixture/alpha-jvm/2.0.0/alpha-jvm-2.0.0.pom"))
        assertEquals("2.0.0", pom.text("/project/dependencyManagement/dependencies/dependency[artifactId='bom']/version"))
        assertEquals("import", pom.text("/project/dependencyManagement/dependencies/dependency[artifactId='bom']/scope"))
    }

    /**
     * A build shaped like kcoap's: a module of the UNIVERSAL tier, one of the JVM_LIBRARY tier, one marked
     * kcoap.unpublished, and the BOM. Nothing in it is compiled.
     */
    private fun kcoapLikeBuild(): Fixture = Fixture(directory, "base")
        .include(":universal", ":jvmonly", ":unpublished", ":bom")
        .file("universal/build.gradle.kts", """plugins { id("kcoap.kmp-library") }""")
        .file("jvmonly/build.gradle.kts", """plugins { id("kcoap.kmp-jvm-library") }""")
        .file(
            "unpublished/build.gradle.kts",
            """
            plugins {
                id("kcoap.unpublished")
                id("kcoap.kmp-library")
            }
            """,
        )
        .file("bom/build.gradle.kts", """plugins { id("kcoap.bom") }""")

    private fun parse(file: File): Document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    private fun Document.text(xpath: String): String = XPathFactory.newInstance().newXPath().evaluate(xpath, this)

    private fun Document.constraints(): List<String> {
        val nodes = XPathFactory.newInstance().newXPath()
            .evaluate("/project/dependencyManagement/dependencies/dependency", this, XPathConstants.NODESET) as NodeList
        return (0 until nodes.length).map { index ->
            val dependency = nodes.item(index) as Element
            fun text(tag: String) = dependency.getElementsByTagName(tag).item(0).textContent.trim()
            "${text("groupId")}:${text("artifactId")}:${text("version")}"
        }
    }
}

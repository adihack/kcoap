import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList

/**
 * The artifacts of one published kcoap module that kcoap-bom constrains: the Kotlin Multiplatform module
 * itself, which Gradle resolves to the right target through its module metadata, and its JVM artifact,
 * which Maven builds (JVM servers) name directly, since Maven reads no Gradle module metadata. The other
 * targets' artifacts (Apple, Linux, js, wasmJs) are reached only through the multiplatform module, whose
 * version the BOM already sets.
 */
fun bomArtifactIds(module: String): List<String> = listOf(module, "$module-jvm")

/**
 * Checks the BOM as it is published against what the build publishes: the constraints in its POM
 * (`dependencyManagement`) and in its Gradle module metadata (`dependencyConstraints` of every variant) must
 * each be exactly [expected], `group:artifact:version` per published module's multiplatform and JVM
 * publication. A module that publishes but is missing from the BOM, a constraint on something that is not
 * published (kcoap-dtls, a typo), or a wrong group or version fails the build, naming each difference.
 */
@CacheableTask
abstract class VerifyBomConstraints : DefaultTask() {
    /** The BOM's generated POM. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val pom: RegularFileProperty

    /** The BOM's generated Gradle module metadata. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val moduleMetadata: RegularFileProperty

    /** `group:artifact:version` of every artifact the BOM must constrain, read from the publications. */
    @get:Input
    abstract val expected: SetProperty<String>

    /** The BOM's own coordinates, for the messages. */
    @get:Input
    abstract val bomName: Property<String>

    /** The constraints found, one per line, so the check is up to date until an input changes. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = expected.get()
        check(expected.isNotEmpty()) { "${bomName.get()}: the build publishes no module to constrain" }
        val inPom = pomConstraints(pom.get().asFile)
        val variants = moduleMetadataConstraints(moduleMetadata.get().asFile)
        val problems = differences("POM", inPom, expected) +
            (if (variants.isEmpty()) listOf("module metadata has no variants") else emptyList()) +
            variants.flatMap { (variant, found) -> differences("module metadata ($variant)", found, expected) }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "${bomName.get()} does not constrain exactly the published modules:\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
        report.get().asFile.writeText(inPom.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun differences(where: String, found: Collection<String>, expected: Set<String>): List<String> {
        val duplicates = found.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        return (expected - found.toSet()).sorted().map { "$where lacks $it" } +
            (found.toSet() - expected).sorted().map { "$where constrains $it, which is not published" } +
            duplicates.sorted().map { "$where constrains $it more than once" }
    }

    private fun pomConstraints(file: File): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = XPathFactory.newInstance().newXPath()
            .evaluate("/project/dependencyManagement/dependencies/dependency", document, XPathConstants.NODESET) as NodeList
        return (0 until nodes.length).map { index ->
            val dependency = nodes.item(index) as Element
            fun text(tag: String) = dependency.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            "${text("groupId")}:${text("artifactId")}:${text("version")}"
        }
    }

    /**
     * The constraints of every variant of the module metadata (apiElements, runtimeElements), by variant name.
     * A variant without `dependencyConstraints` has none, and fails the check like any other that lacks
     * some: each variant must hold the whole set, since a consumer reads only the one it selects.
     */
    private fun moduleMetadataConstraints(file: File): List<Pair<String, List<String>>> {
        val module = JsonSlurper().parse(file) as? Map<*, *> ?: return emptyList()
        val variants = module["variants"] as? List<*> ?: return emptyList()
        return variants.map { variant ->
            variant as Map<*, *>
            val constraints = variant["dependencyConstraints"] as? List<*> ?: emptyList<Any>()
            variant["name"].toString() to constraints.map { constraint ->
                constraint as Map<*, *>
                val version = (constraint["version"] as? Map<*, *>)?.get("requires") ?: ""
                "${constraint["group"]}:${constraint["module"]}:$version"
            }
        }
    }
}

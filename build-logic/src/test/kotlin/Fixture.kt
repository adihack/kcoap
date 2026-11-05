import org.gradle.testkit.runner.GradleRunner
import java.io.File

/**
 * A one-module Gradle build that applies kcoap's convention plugins as a kcoap module does, run with
 * Gradle TestKit. The plugins come from this build's classpath; the version catalog is the repository's.
 */
class Fixture(private val directory: File, vararg plugins: String) {
    /** Versions that replace the catalog's, as calls in the settings script. */
    private val catalogVersions = mutableListOf<String>()

    init {
        writeSettings()
        file(
            "gradle.properties",
            """
            kcoap.group=dev.kcoap.fixture
            kcoap.version=1.0.0
            kotlin.native.ignoreDisabledTargets=true
            org.gradle.jvmargs=-Xmx1g -XX:MaxMetaspaceSize=512m
            # One small daemon for the whole test run, gone two minutes after the last fixture build.
            org.gradle.daemon.idletimeout=120000
            # A fixture that compiles Kotlin does so in that daemon, without a Kotlin daemon of its own.
            kotlin.compiler.execution.strategy=in-process
            """,
        )
        file(
            "build.gradle.kts",
            plugins.joinToString("\n", "plugins {\n", "\n}\n") { "    id(\"$it\")" } +
                // `check --dry-run` would resolve every target's toolchain (Node.js included) and need the
                // network, so the fixture reports what `check` depends on instead.
                """
                val check = tasks.named("check")
                tasks.register("checkDependencies") {
                    doLast {
                        val names = check.get().dependsOn.map { (it as? TaskProvider<*>)?.name ?: it.toString() }
                        println("check depends on: " + names.sorted().joinToString())
                    }
                }
                """.trimIndent(),
        )
        file("src/commonMain/kotlin/dev/kcoap/fixture/Common.kt", "package dev.kcoap.fixture\n\npublic fun common(): Int = 1\n")
    }

    /** Replaces the version [alias] of the repository's version catalog with [version] in this fixture. */
    fun catalogVersion(alias: String, version: String): Fixture = apply {
        catalogVersions += "version(\"$alias\", \"$version\")"
        writeSettings()
    }

    /** Writes [content], without its common indentation, to [path] inside the fixture. */
    fun file(path: String, content: String): Fixture = apply {
        directory.resolve(path).apply { parentFile.mkdirs() }.writeText(content.trimIndent() + "\n")
    }

    /** Appends [content], without its common indentation, to the fixture's build.gradle.kts. */
    fun buildScript(content: String): Fixture = apply {
        directory.resolve("build.gradle.kts").appendText("\n" + content.trimIndent() + "\n")
    }

    fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
        .withProjectDir(directory)
        .withTestKitDir(File(systemProperty("kcoap.testKitDir")))
        .withPluginClasspath()
        .withArguments(*arguments, "--stacktrace")
        .forwardOutput()

    private fun writeSettings() {
        val catalog = (listOf("from(files(\"${systemProperty("kcoap.versionCatalog")}\"))") + catalogVersions)
            .joinToString("; ")
        file(
            "settings.gradle.kts",
            """
            rootProject.name = "fixture"
            dependencyResolutionManagement {
                repositories { mavenCentral() }
                versionCatalogs {
                    create("libs") { $catalog }
                }
            }
            """,
        )
    }

    private fun systemProperty(name: String): String =
        checkNotNull(System.getProperty(name)) { "$name is set by build-logic/build.gradle.kts" }
}

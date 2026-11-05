import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * kcoap.android-api keeps a module's jvm jar within kcoap's minSdk, Android API level 26, and its libraries
 * within what was triaged.
 *
 * The multiplatform fixtures apply a tier plugin, as kcoap's modules do, and look at the tasks the plugin
 * registers: how they are configured, that `check` runs them, and that checkAndroidApi fails a module whose
 * Kotlin calls an API the level lacks. The Java fixtures compile quicker and test the task type,
 * CheckAndroidApi, configured from the version catalog in the same way: animal-sniffer fails a jar that
 * uses a Java API the level lacks, and passes the level's own API, the classes that ship with the jar and
 * the bootstraps D8 rewrites; a baseline lets through only the uses it lists.
 */
class AndroidApiPluginTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun checkRunsCheckAndroidApiOnTheJvmJarAndCheckAndroidApiDependenciesOnTheLibraries() {
        val module = Fixture(directory, "kcoap.kmp-library").buildScript(
            """
            val checks = listOf("checkAndroidApi", "checkAndroidApiDependencies").map { tasks.named<CheckAndroidApi>(it) }
            val projectDirectory = layout.projectDirectory.asFile
            tasks.register("printAndroidApiChecks") {
                doLast {
                    for (task in checks.map { it.get() }) {
                        println("${'$'}{task.name} classes: " + task.classes.files.map { it.name }.sorted().joinToString())
                        println("${'$'}{task.name} signature: " + task.signature.files.joinToString { it.name })
                        println("${'$'}{task.name} apiLevel: " + task.apiLevel.get())
                        println("${'$'}{task.name} failOnViolation: " + task.failOnViolation.get())
                        val baseline = task.baseline.orNull?.asFile?.relativeTo(projectDirectory)
                        println("${'$'}{task.name} baseline: " + (baseline ?: "none"))
                    }
                }
            }
            """,
        )

        val result = module.runner("checkDependencies", "printAndroidApiChecks").build()

        val lines = result.output.lines()
        val checkDependencies = lines.single { it.startsWith("check depends on: ") }.substringAfter(": ").split(", ")
        assertContains(checkDependencies, "checkAndroidApi")
        assertContains(checkDependencies, "checkAndroidApiDependencies")
        val signature = "android-api-level-26-8.0.0_r2.signature"
        for (expected in listOf(
            "checkAndroidApi classes: fixture-jvm-1.0.0.jar",
            "checkAndroidApi signature: $signature",
            "checkAndroidApi apiLevel: 26",
            "checkAndroidApi failOnViolation: true",
            "checkAndroidApi baseline: none",
            "checkAndroidApiDependencies signature: $signature",
            "checkAndroidApiDependencies apiLevel: 26",
            "checkAndroidApiDependencies failOnViolation: true",
            "checkAndroidApiDependencies baseline: gradle/android-api-dependencies.baseline",
        )) {
            assertContains(lines, expected)
        }
        // The external libraries only: the module's own jar is checkAndroidApi's.
        val libraries = lines.single { it.startsWith("checkAndroidApiDependencies classes: ") }
        assertContains(libraries, "kotlin-stdlib-")
        assertFalse(libraries.contains("fixture-jvm"), libraries)
    }

    @Test
    fun aMultiplatformModuleWhoseJvmCodeCallsAnApiAboveMinSdkFailsCheckAndroidApi() {
        val module = Fixture(directory, "kcoap.kmp-library").file(
            "src/jvmMain/kotlin/dev/kcoap/fixture/Hex.kt",
            """
            package dev.kcoap.fixture

            public fun hex(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(bytes)
            """,
        )

        val result = module.runner("checkAndroidApi").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":checkAndroidApi")?.outcome)
        assertContains(result.output, "uses of Java APIs that Android API level 26 does not have")
        assertContains(result.output, "fixture-jvm-1.0.0.jar: dev/kcoap/fixture/HexKt.class:")
        assertContains(result.output, "Undefined reference: java.util.HexFormat java.util.HexFormat.of()")
        assertContains(directory.resolve("build/reports/android-api/jvm.txt").readText(), "HexKt.class")
    }

    @Test
    fun anApiSignatureOfAnotherLevelThanMinSdkFailsTheBuild() {
        val module = Fixture(directory, "kcoap.kmp-library").catalogVersion("android-minSdk", "24")

        val result = module.runner("help").buildAndFail()

        assertContains(
            result.output,
            "The version catalog's android-api-signature (android-api-level-26) must be the one of android-minSdk (24)",
        )
    }

    @Test
    fun aJarUsingAJavaApiAboveMinSdkFailsCheckAndroidApi() {
        val fixture = jarFixture(HEX_ABOVE_MIN_SDK)

        val result = fixture.runner("checkJar").buildAndFail()

        assertEquals(TaskOutcome.FAILED, result.task(":checkJar")?.outcome)
        assertContains(result.output, "uses of Java APIs that Android API level 26 does not have")
        assertContains(result.output, "fixture.jar: dev/kcoap/fixture/Uses.class:")
        assertContains(result.output, "Undefined reference: java.util.HexFormat java.util.HexFormat.of()")
    }

    @Test
    fun theApiOfMinSdkTheShippedClasspathLambdasAndStringConcatenationPass() {
        // java.time and Base64 came with API level 26. Helper ships with the jar and is not checked itself,
        // though it uses HexFormat. The lambda and the concatenation are invokedynamic, which D8 rewrites.
        val fixture = jarFixture(
            """
            public static String text() {
                java.util.function.Supplier<String> base64 =
                    () -> java.util.Base64.getEncoder().encodeToString(new byte[1]);
                return java.time.Instant.now() + base64.get() + dep.Helper.help();
            }
            """,
        )

        val result = fixture.runner("checkJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkJar")?.outcome)
        assertEquals("", report().trim())
    }

    @Test
    fun aUseInsideATryIsStillAViolation() {
        // The catch may rethrow or do nothing; only the code's author knows that the use is guarded.
        val fixture = jarFixture(
            """
            public static String hex(byte[] bytes) {
                try {
                    return java.util.HexFormat.of().formatHex(bytes);
                } catch (NoClassDefFoundError notOnThisDevice) {
                    return null;
                }
            }
            """,
        )

        val result = fixture.runner("checkJar").buildAndFail()

        assertContains(result.output, "Undefined reference: java.util.HexFormat java.util.HexFormat.of()")
    }

    @Test
    fun withoutFailOnViolationTheViolationsAreReportedAndTheTaskPasses() {
        val fixture = jarFixture(HEX_ABOVE_MIN_SDK, failOnViolation = false)

        val result = fixture.runner("checkJar").build()

        assertEquals(TaskOutcome.SUCCESS, result.task(":checkJar")?.outcome)
        assertContains(result.output, "uses of Java APIs that Android API level 26 does not have")
        assertContains(report(), "fixture.jar: dev/kcoap/fixture/Uses.class:")
    }

    @Test
    fun withABaselineOnlyTheUsesItDoesNotListFailAndTheMessageGivesTheirLines() {
        // The triage: the uses found so far go into the baseline, which the check then accepts.
        jarFixture(HEX_ABOVE_MIN_SDK, failOnViolation = false).runner("checkJar").build()
        val triaged = report().lines().filter { it.isNotBlank() }.map(::baselineEntry)
        directory.resolve("baseline.txt").writeText("# triaged\n\n" + triaged.joinToString("\n") + "\n")
        val fixture = jarFixture(HEX_ABOVE_MIN_SDK, baseline = "baseline.txt")

        assertEquals(TaskOutcome.SUCCESS, fixture.runner("checkJar").build().task(":checkJar")?.outcome)

        // A library update brings a new use.
        val updated = jarFixture("$HEX_ABOVE_MIN_SDK\n$PARSE_ABOVE_MIN_SDK", baseline = "baseline.txt")
        val result = updated.runner("checkJar").buildAndFail()

        val message = result.output.substringAfter("does not list").substringBefore("* Try:")
        assertContains(message, "baseline.txt")
        assertContains(message, "fixture: dev/kcoap/fixture/Uses.class: Undefined reference: int java.util.HexFormat.fromHexDigits(CharSequence)")
        assertFalse(message.contains("java.util.HexFormat.of()"), message)
        assertContains(report(), "java.util.HexFormat.of()")
    }

    @Test
    fun aBaselineEntryIsTheJarWithoutItsVersionTheClassFileAndTheUse() {
        assertEquals(
            "ktor-utils-jvm: io/ktor/util/debug/IntellijIdeaDebugDetector.class: Undefined reference: java.lang.management.RuntimeMXBean",
            baselineEntry(
                "ktor-utils-jvm-3.6.1.jar: io/ktor/util/debug/IntellijIdeaDebugDetector.class:12: " +
                    "Undefined reference: java.lang.management.RuntimeMXBean",
            ),
        )
        // A digit inside the artifact id stays; a class file without a line number stays as it is.
        assertEquals(
            "bcprov-jdk18on: org/bouncycastle/jce/provider/CrlCache.class: Undefined reference: javax.naming.NamingException",
            baselineEntry(
                "bcprov-jdk18on-1.86.jar: org/bouncycastle/jce/provider/CrlCache.class: " +
                    "Undefined reference: javax.naming.NamingException",
            ),
        )
        assertEquals(
            "fixture: a/B\$1.class: Undefined reference: void ClassValue.<init>()",
            baselineEntry("fixture.jar: a/B\$1.class:7: Undefined reference: void ClassValue.<init>()"),
        )
    }

    private fun report(): String = directory.resolve("build/android-api.txt").readText()

    /**
     * A Java library whose class `dev.kcoap.fixture.Uses` has [members], with a class `dep.Helper` on its
     * runtime classpath, and a CheckAndroidApi task `checkJar` over its jar against the API level of
     * android-minSdk, configured from the version catalog as kcoap.android-api does, with the [baseline]
     * file when one is given.
     */
    private fun jarFixture(members: String, failOnViolation: Boolean = true, baseline: String? = null): Fixture =
        Fixture(directory, "java-library", "kcoap.android-api")
            .file(
                "src/main/java/dev/kcoap/fixture/Uses.java",
                "package dev.kcoap.fixture;\n\npublic class Uses {\n$members\n}",
            )
            .file(
                "src/dependency/java/dep/Helper.java",
                """
                package dep;

                public class Helper {
                    public static String help() { return java.util.HexFormat.of().toString(); }
                }
                """,
            )
            .buildScript(
                """
                val dependency = sourceSets.create("dependency")
                dependencies { implementation(dependency.output) }

                val apiSignature = libs.android.api.signature.get().let {
                    val notation = "${'$'}{it.module}:${'$'}{it.version}@signature"
                    configurations.detachedConfiguration(dependencies.create(notation))
                }
                val animalSniffer = configurations.detachedConfiguration(dependencies.create(libs.animal.sniffer.get()))
                tasks.register<CheckAndroidApi>("checkJar") {
                    classes.from(tasks.jar)
                    classpath.from(configurations.runtimeClasspath)
                    signature.from(apiSignature)
                    apiLevel.set(libs.versions.android.minSdk.get().toInt())
                    toolClasspath.from(animalSniffer)
                    failOnViolation.set($failOnViolation)
                    ${if (baseline == null) "" else "baseline.set(file(\"$baseline\"))"}
                    report.set(layout.buildDirectory.file("android-api.txt"))
                }
                """,
            )

    private companion object {
        /** A member of `Uses` that calls java.util.HexFormat, which came with Java 17 and Android API level 34. */
        const val HEX_ABOVE_MIN_SDK: String =
            "public static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }"

        /** Another member of `Uses` with another HexFormat call. */
        const val PARSE_ABOVE_MIN_SDK: String =
            "public static int parse(String digits) { return java.util.HexFormat.fromHexDigits(digits); }"
    }
}

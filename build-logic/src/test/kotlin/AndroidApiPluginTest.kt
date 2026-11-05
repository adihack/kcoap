import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * checkAndroidApi keeps a module's jvm jar within kcoap's minSdk, Android API level 26: animal-sniffer
 * fails a jar that uses a Java API the level lacks, and passes the level's own API, the classes that ship
 * with the jar and the bootstraps D8 rewrites. The fixtures compile Java, which is quick, and run the task
 * on their jar; a multiplatform fixture shows that check includes it.
 */
class AndroidApiPluginTest {
    @TempDir
    lateinit var directory: File

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
        assertEquals("", directory.resolve("build/android-api.txt").readText().trim())
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
        val report = directory.resolve("build/android-api.txt").readText()
        assertContains(report, "fixture.jar: dev/kcoap/fixture/Uses.class:")
    }

    @Test
    fun aMultiplatformModuleChecksItsJvmJarAsPartOfCheck() {
        val module = Fixture(directory, "kcoap.kmp-library")

        val result = module.runner("checkDependencies", "tasks", "--all").build()

        assertContains(result.output.lines().single { it.startsWith("check depends on: ") }, "checkAndroidApi")
        assertContains(result.output, "androidApiDependencyReport")
    }

    /**
     * A Java library whose class `dev.kcoap.fixture.Uses` has [members], with a class `dep.Helper` on its
     * runtime classpath, and a CheckAndroidApi task `checkJar` over its jar against the API level of
     * android-minSdk, configured from the version catalog as kcoap.android-api does.
     */
    private fun jarFixture(members: String, failOnViolation: Boolean = true): Fixture =
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
                    report.set(layout.buildDirectory.file("android-api.txt"))
                }
                """,
            )

    private companion object {
        /** A member of `Uses` that calls java.util.HexFormat, which came with Java 17 and Android API level 34. */
        const val HEX_ABOVE_MIN_SDK: String =
            "public static String hex(byte[] bytes) { return java.util.HexFormat.of().formatHex(bytes); }"
    }
}

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** Which Kotlin targets a kcoap module builds for. */
enum class KcoapTargets {
    /** JVM (servers and Android), iOS, macOS and Linux (gateways; also runs the native tests on Linux CI). */
    ALL,

    /** JVM only, for modules wrapping JVM libraries such as Bouncy Castle. */
    JVM,
}

/** Settings shared by every kcoap library module: targets, explicit API, JVM level, test setup. */
fun Project.configureKcoapLibrary(targets: KcoapTargets) {
    group = providers.gradleProperty("kcoap.group").get()
    version = providers.gradleProperty("kcoap.version").get()

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

    extensions.getByType<KotlinMultiplatformExtension>().apply {
        explicitApi()

        jvm {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
        if (targets == KcoapTargets.ALL) {
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            linuxX64()
        }

        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    // -Pkcoap.tests=unit runs everything except the interop tests, -Pkcoap.tests=interop only those;
    // without the property every test runs, and interop tests skip themselves when Docker is missing.
    val selection = providers.gradleProperty("kcoap.tests").orElse("all").get()
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        filter {
            isFailOnNoMatchingTests = false
            when (selection) {
                "unit" -> INTEROP_TEST_PATTERNS.forEach { excludeTestsMatching(it) }
                "interop" -> INTEROP_TEST_PATTERNS.forEach { includeTestsMatching(it) }
                "all" -> Unit
                else -> error("kcoap.tests must be unit, interop or all, not '$selection'")
            }
        }
    }
}

/** Interop tests are named *Interop* or live in an `interop` package. */
private val INTEROP_TEST_PATTERNS = listOf("*Interop*", "*.interop.*")

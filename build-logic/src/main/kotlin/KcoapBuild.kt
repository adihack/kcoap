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
    /** JVM (servers and Android), iOS and macOS. */
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
        }

        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

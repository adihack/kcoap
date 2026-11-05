import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.the
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.dsl.KotlinJsTargetDsl
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootEnvSpec

/**
 * Which Kotlin targets a kcoap module builds for. Protocol and core modules are common code and build
 * for [ALL]; only an adapter module that wraps a library bound to particular runtimes narrows its
 * targets, to the runtimes that library has.
 */
enum class KcoapTargets {
    /** Every target: [NATIVE_AND_JVM] plus the web, `js` and `wasmJs` (browsers and Node.js). */
    ALL,

    /**
     * JVM (servers and Android), iOS, macOS and Linux (gateways; also runs the native tests on Linux CI),
     * for adapters that need sockets, which browsers do not have: UDP on ktor-network.
     */
    NATIVE_AND_JVM,

    /** JVM only, for adapters wrapping JVM libraries such as Bouncy Castle. */
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
        if (targets == KcoapTargets.ALL || targets == KcoapTargets.NATIVE_AND_JVM) {
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            linuxX64()
        }
        if (targets == KcoapTargets.ALL) {
            js {
                webEnvironments()
                nodejs {
                    testTask {
                        useMocha {
                            // Mocha's default of 2 s per test is too short for the full-stack test: EDHOC,
                            // OSCORE and block-wise transfers on a lossy simulated network, where every
                            // WebCrypto call is a real asynchronous round trip.
                            timeout = "60s"
                        }
                    }
                }
            }
            @OptIn(ExperimentalWasmDsl::class)
            wasmJs { webEnvironments() }
        }

        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
    }

    useSettingsRepositoriesForNodeJs()

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}

/**
 * Browsers for applications and Node.js for both applications and the tests. The common tests run on
 * Node (`jsNodeTest`, `wasmJsNodeTest`), which has the same WebCrypto API as browsers; a browser run
 * would need a headless browser on every machine that runs `check`, so `jsBrowserTest` and
 * `wasmJsBrowserTest` are off.
 */
private fun KotlinJsTargetDsl.webEnvironments() {
    browser {
        testTask { enabled = false }
    }
    nodejs()
}

/**
 * Node.js and Yarn, which the js and wasmJs tests run on, come from the repositories declared in
 * settings.gradle.kts. Without a download URL the Kotlin plugin adds no repository of its own, which
 * settings would ignore with a warning. Applied to the root project too, where Yarn is set up.
 */
fun Project.useSettingsRepositoriesForNodeJs() {
    plugins.withType<NodeJsPlugin> { the<NodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
    plugins.withType<WasmNodeJsPlugin> { the<WasmNodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
    plugins.withType<YarnPlugin> { the<YarnRootEnvSpec>().downloadBaseUrl.set(null as String?) }
    plugins.withType<WasmYarnPlugin> { the<WasmYarnRootEnvSpec>().downloadBaseUrl.set(null as String?) }
}

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
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
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
 * The target tiers of kcoap's modules (docs/target-architecture.md §2.1). Core modules are common code
 * and build for [UNIVERSAL]; an adapter that wraps a library bound to particular runtimes declares the
 * smallest tier that library supports. Each tier has one convention plugin: `kcoap.kmp-library`,
 * `kcoap.kmp-jvm-native-library` and `kcoap.kmp-jvm-library`.
 */
enum class KcoapTargets {
    /** Every target: [SOCKETS] plus the web, `js` and `wasmJs` (browsers and Node.js). */
    UNIVERSAL,

    /**
     * JVM (servers and Android), iOS, macOS and Linux (gateways; also runs the native tests on Linux CI),
     * for adapters that open sockets: ktor-network for UDP and TCP, a ktor server engine. Never the web:
     * one js artifact serves browsers and Node.js, so sockets there would ship to browsers.
     */
    SOCKETS,

    /** JVM only (servers and Android), for adapters wrapping JVM libraries such as Bouncy Castle. */
    JVM_LIBRARY,
}

/**
 * kcoap's plumbing between its own modules (`dev.kcoap.common.InternalKcoapApi`). Every kcoap module
 * opts in, and the ABI dumps leave it out; applications would have to opt in explicitly.
 */
const val INTERNAL_API_MARKER: String = "dev.kcoap.common.InternalKcoapApi"

/** The plugin that keeps a module out of the published set (`kcoap.unpublished.gradle.kts`). */
const val UNPUBLISHED_PLUGIN: String = "kcoap.unpublished"

/**
 * Settings shared by every kcoap library module: targets, explicit API, the internal-API opt-in, JVM
 * level and test setup. Then, unless [UNPUBLISHED_PLUGIN] was applied first, publishing and the ABI
 * dumps of the public API.
 */
fun Project.configureKcoapLibrary(targets: KcoapTargets) {
    group = providers.gradleProperty("kcoap.group").get()
    version = providers.gradleProperty("kcoap.version").get()

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val multiplatform = extensions.getByType<KotlinMultiplatformExtension>()

    multiplatform.apply {
        explicitApi()

        jvm {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
                freeCompilerArgs.add("-Xjdk-release=17")
            }
        }
        if (targets == KcoapTargets.UNIVERSAL || targets == KcoapTargets.SOCKETS) {
            iosArm64()
            iosSimulatorArm64()
            macosArm64()
            linuxX64()
        }
        if (targets == KcoapTargets.UNIVERSAL) {
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

        // kcoap's modules share helpers marked @InternalKcoapApi (byte builders, option codecs); only
        // applications must opt in to them.
        sourceSets.configureEach {
            languageSettings.optIn(INTERNAL_API_MARKER)
        }
        sourceSets.getByName("commonTest").dependencies {
            implementation(kotlin("test"))
            implementation(libs.findLibrary("kotlinx-coroutines-test").get())
        }
        // Tests drive virtual time with advanceTimeBy and runCurrent, which coroutines marks experimental.
        sourceSets.matching { it.name.endsWith("Test") }.configureEach {
            languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        }
    }

    useSettingsRepositoriesForNodeJs()

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

    // Applied last, so the coordinates and the targets are known. kcoap.unpublished, when it is wanted,
    // must already be applied: it fails the build if publishing comes first.
    if (!pluginManager.hasPlugin(UNPUBLISHED_PLUGIN)) {
        pluginManager.apply("kcoap.publishing")
        multiplatform.validateAbi()
    }
}

/**
 * Turns on the Kotlin Gradle plugin's ABI validation: `checkKotlinAbi` (part of `check`) compares the
 * public API with the dumps committed under `api/`, and `updateKotlinAbi` rewrites them. The dumps cover
 * the JVM class files and the klibs of every other target. Declarations marked [INTERNAL_API_MARKER]
 * are left out.
 *
 * A host that cannot build a target keeps that target's entries from the committed klib dump instead
 * of dropping them, so a dump updated on Linux still lists the Apple targets. Linux builds Apple klibs
 * today (klib cross-compilation); docs/releasing.md says how to refresh them on macOS when it cannot.
 */
@OptIn(ExperimentalAbiValidation::class)
private fun KotlinMultiplatformExtension.validateAbi() {
    abiValidation {
        keepLocallyUnsupportedTargets.set(true)
        filters {
            exclude {
                annotatedWith.add(INTERNAL_API_MARKER)
            }
        }
    }
}

/** Interop tests are named *Interop* or live in an `interop` package. */
private val INTEROP_TEST_PATTERNS = listOf("*Interop*", "*.interop.*")

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

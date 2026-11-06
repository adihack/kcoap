import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootEnvSpec

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// The kcoap version to use: -Pkcoap.version, as publishToMavenLocal was given it, or else the one in the
// repository's gradle.properties, which a plain publishToMavenLocal publishes. It is written once, for the BOM.
val kcoapVersion: String = providers.gradleProperty("kcoap.version").orElse(
    providers.fileContents(layout.settingsDirectory.file("../../gradle.properties")).asText.map { text ->
        Properties().apply { load(text.reader()) }.getProperty("kcoap.version")
    },
).get()

kotlin {
    // The browser application: Kotlin/JS (IR), bundled by webpack. jsBrowserDistribution writes the production
    // bundle, minified, with index.html into build/dist/js/productionExecutable.
    js {
        browser {
            // No js tests: they would need a browser on every machine that builds the sample. jvmTest runs the
            // bundle instead, on Node.js and, when -Pkcoap.browser names one, in a browser (below).
            testTask { enabled = false }
        }
        binaries.executable()
    }
    // The server the page talks to, and the tests, on the JVM.
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }

    sourceSets {
        commonMain.dependencies {
            // A minimal browser client with OSCORE: the CoAP stack, OSCORE, the platform's cryptography (WebCrypto in
            // browsers) and the WebSocket connector, every version from kcoap-bom. EDHOC would add kcoap-edhoc and
            // kcoap-edhoc-oscore (docs/getting-started.md, "By platform").
            implementation(project.dependencies.platform("dev.kcoap:kcoap-bom:$kcoapVersion"))
            implementation("dev.kcoap:kcoap-coap")
            implementation("dev.kcoap:kcoap-oscore")
            implementation("dev.kcoap:kcoap-crypto-default")
            implementation("dev.kcoap:kcoap-transport-ktor-websocket")
        }
        jvmMain.dependencies {
            // The sample's server: CoAP over WebSockets in a ktor application on the CIO engine, which the
            // application chooses (kcoap brings none).
            implementation("dev.kcoap:kcoap-transport-ktor-websocket-server")
            implementation(libs.ktor.server.cio)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// Node.js and Yarn come from the repositories in settings.gradle.kts: without a download URL the Kotlin plugin adds
// no repository of its own, which this build would refuse.
plugins.withType<NodeJsPlugin> { the<NodeJsEnvSpec>().downloadBaseUrl.set(null as String?) }
plugins.withType<YarnPlugin> { the<YarnRootEnvSpec>().downloadBaseUrl.set(null as String?) }

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val productionBundle = tasks.named<KotlinWebpack>("jsBrowserProductionWebpack")
val distribution = tasks.named("jsBrowserDistribution")

/**
 * The production bundle's size, minified as webpack writes it and gzip-compressed at level 9 (`gzip -9`), against
 * the budget in bundle-budget.properties: it fails when either size exceeds its budget. Source maps and extracted
 * license comments are not shipped to browsers, so they do not count.
 */
@CacheableTask
abstract class CheckBundleSize : DefaultTask() {
    /** The JavaScript webpack wrote. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val bundle: ConfigurableFileCollection

    /** The budget file, `minified` and `gzip` in bytes. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val budget: RegularFileProperty

    /** The measurement, one line per figure. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val files = bundle.asFileTree.files.filter { it.name.endsWith(".js") }.sortedBy { it.name }
        if (files.isEmpty()) throw GradleException("No production bundle in ${bundle.files}")
        val bytes = files.fold(ByteArray(0)) { all, file -> all + file.readBytes() }
        val gzip = ByteArrayOutputStream().also { out ->
            object : GZIPOutputStream(out) {
                init {
                    def.setLevel(Deflater.BEST_COMPRESSION)
                }
            }.use { it.write(bytes) }
        }.size().toLong()
        val limits = Properties().apply { budget.get().asFile.reader().use { load(it) } }
        fun limit(name: String): Long = limits.getProperty(name)?.trim()?.toLongOrNull()
            ?: throw GradleException("${budget.get().asFile.name} has no number for '$name'")
        val minifiedBudget = limit("minified")
        val gzipBudget = limit("gzip")
        val lines = listOf(
            "bundle: ${files.joinToString(", ") { it.name }}",
            "minified: ${bytes.size} bytes, budget $minifiedBudget",
            "gzip -9: $gzip bytes, budget $gzipBudget",
        )
        report.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
        lines.forEach { logger.lifecycle(it) }
        val over = listOfNotNull(
            "minified ${bytes.size} > $minifiedBudget".takeIf { bytes.size > minifiedBudget },
            "gzip $gzip > $gzipBudget".takeIf { gzip > gzipBudget },
        )
        if (over.isNotEmpty()) {
            throw GradleException(
                "The production bundle exceeds its budget (${over.joinToString("; ")} bytes). If the growth is meant, " +
                    "raise the budget in ${budget.get().asFile.name} and the measurement in docs/platforms.md " +
                    "(samples/js-consumer/README.md, \"Bundle size\").",
            )
        }
    }
}

val checkBundleSize = tasks.register<CheckBundleSize>("checkBundleSize") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when the production bundle exceeds the budget in bundle-budget.properties."
    bundle.from(productionBundle.flatMap { it.outputDirectory })
    budget = layout.projectDirectory.file("bundle-budget.properties")
    report = layout.buildDirectory.file("reports/bundle-size.txt")
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(checkBundleSize)
}

// jvmTest runs the production bundle against the sample server: on Node.js, the one the Kotlin plugin set up for
// webpack, with a stand-in for the page (NodePageTest), and with -Pkcoap.browser=<browser command> (google-chrome,
// chromium or firefox) in that browser, headless (BrowserSmokeTest, which skips without it). CI's js-consumer job
// names google-chrome; nothing here downloads a browser.
tasks.named<Test>("jvmTest") {
    dependsOn(distribution, "kotlinNodeJsSetup", "kotlinNpmInstall")
    val site = layout.buildDirectory.dir("dist/js/productionExecutable")
    val node = project.the<NodeJsEnvSpec>().executable
    val nodeModules = layout.buildDirectory.dir("js/node_modules")
    val browser = providers.gradleProperty("kcoap.browser").orElse("")
    val work = layout.buildDirectory.dir("browser")
    inputs.files(distribution).withPropertyName("site").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("browser", browser)
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dkcoap.sample.site=${site.get().asFile.path}",
                "-Dkcoap.node=${node.get()}",
                "-Dkcoap.node.modules=${nodeModules.get().asFile.path}",
                "-Dkcoap.browser=${browser.get()}",
                "-Dkcoap.browser.work=${work.get().asFile.path}",
            )
        },
    )
    // A browser run depends on the browser installed, which Gradle cannot see.
    if (browser.get().isNotEmpty()) {
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

// Serves the production bundle and CoAP over WebSockets with OSCORE on http://127.0.0.1:8080/, for a browser.
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Serves the page, its production bundle and CoAP over WebSockets with OSCORE on port 8080."
    dependsOn(distribution)
    val main = kotlin.jvm().compilations.getByName("main")
    classpath(main.output.allOutputs, main.runtimeDependencyFiles)
    mainClass = "dev.kcoap.sample.js.ServerMainKt"
    val site = layout.buildDirectory.dir("dist/js/productionExecutable")
    argumentProviders.add(CommandLineArgumentProvider { listOf(site.get().asFile.path) })
}

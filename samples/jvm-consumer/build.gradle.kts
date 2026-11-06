import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The kcoap version to use: -Pkcoap.version, as publishToMavenLocal was given it, or else the one in the
// repository's gradle.properties, which a plain publishToMavenLocal publishes. It is written once, for the BOM.
val kcoapVersion: String = providers.gradleProperty("kcoap.version").orElse(
    providers.fileContents(layout.settingsDirectory.file("../../gradle.properties")).asText.map { text ->
        Properties().apply { load(text.reader()) }.getProperty("kcoap.version")
    },
).get()

dependencies {
    // kcoap-bom sets the version of every kcoap module, those named here and those they bring along; the
    // modules are named without one (docs/getting-started.md, "Add kcoap to a build").
    implementation(platform("dev.kcoap:kcoap-bom:$kcoapVersion"))
    implementation("dev.kcoap:kcoap-edhoc-oscore") // EDHOC and OSCORE over CoAP; brings the CoAP stack
    implementation("dev.kcoap:kcoap-crypto-default") // the platform's cryptography
    implementation("dev.kcoap:kcoap-transport-ktor") // UDP and TCP sockets
    // Nothing else: what the code uses besides (kotlinx-coroutines, ktor's sockets) comes through kcoap's
    // published api dependencies, so a wrong scope there fails this build.
}

// kcoap's jvm classes are Java 17 bytecode, its minimum, and Kotlin inlines kcoap's inline functions only into
// bytecode of the same target or newer: the sample compiles to Java 17 on whatever JDK runs Gradle.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "dev.kcoap.sample.jvm.MainKt"
}

// Every kcoap module on the runtime classpath must have its version from kcoap-bom: the BOM constrains it, as
// the multiplatform module or its JVM artifact (which Maven builds name), at the BOM's version. Resolution alone
// fails only for the modules named above when the BOM lacks one; this also catches a module that arrives as a
// dependency of another, and so has a version without the BOM, and a module at a version the BOM does not set.
val verifyKcoapFromBom = tasks.register("verifyKcoapFromBom") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when a kcoap module on the runtime classpath is not constrained by kcoap-bom."
    val graph = configurations.runtimeClasspath.flatMap { it.incoming.resolutionResult.rootComponent }
    val version = kcoapVersion
    doLast {
        val components = mutableSetOf<ResolvedComponentResult>()
        fun visit(component: ResolvedComponentResult) {
            if (components.add(component)) {
                component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { visit(it.selected) }
            }
        }
        visit(graph.get())
        val kcoap = components.mapNotNull { it.moduleVersion }.filter { it.group == "dev.kcoap" }
        val bom = components.singleOrNull { it.moduleVersion?.let { id -> id.group == "dev.kcoap" && id.name == "kcoap-bom" } == true }
            ?: throw GradleException("kcoap-bom is not on the runtime classpath")
        val constrained = bom.dependencies.filter { it.isConstraint }
            .mapNotNull { (it.requested as? ModuleComponentSelector)?.takeIf { selector -> selector.group == "dev.kcoap" } }
            .associate { it.module to it.version }
        val problems = kcoap.filter { it.name != "kcoap-bom" }.mapNotNull { module ->
            when {
                module.name !in constrained -> "${module.name}:${module.version} is not constrained by kcoap-bom:$version"
                constrained[module.name] != version -> "kcoap-bom:$version constrains ${module.name} to ${constrained[module.name]}"
                module.version != version -> "${module.name} resolved to ${module.version}, not the BOM's $version"
                else -> null
            }
        }
        if (problems.isNotEmpty()) throw GradleException("kcoap modules outside the BOM:\n  " + problems.joinToString("\n  "))
        if (kcoap.none { it.name == "kcoap-coap-jvm" }) throw GradleException("No kcoap module resolved: $kcoap")
        logger.lifecycle("kcoap-bom:$version constrains all ${kcoap.size - 1} kcoap modules on the runtime classpath")
    }
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyKcoapFromBom)
}

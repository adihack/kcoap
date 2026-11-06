import java.util.Properties
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The kcoap version, as samples/jvm-consumer takes it: -Pkcoap.version, or else the repository's gradle.properties.
val kcoapVersion: String = providers.gradleProperty("kcoap.version").orElse(
    providers.fileContents(layout.settingsDirectory.file("../../../gradle.properties")).asText.map { text ->
        Properties().apply { load(text.reader()) }.getProperty("kcoap.version")
    },
).get()

dependencies {
    // What a Maven build writes: kcoap-bom imported (<scope>import</scope>), then the JVM artifacts without a
    // version (docs/getting-started.md, "Add kcoap to a build"). Gradle reads the BOM's dependencyManagement from
    // its POM as constraints, as Maven does.
    implementation(platform("dev.kcoap:kcoap-bom:$kcoapVersion"))
    implementation("dev.kcoap:kcoap-edhoc-oscore-jvm")
    implementation("dev.kcoap:kcoap-crypto-default-jvm")
    implementation("dev.kcoap:kcoap-transport-ktor-jvm")
    // Nothing else: what the code uses besides (kotlinx-coroutines, ktor's sockets) comes through the compile
    // scope of kcoap's POMs.
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
    // The program of samples/jvm-consumer, unchanged.
    sourceSets.main { kotlin.srcDir("../src/main/kotlin") }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application {
    mainClass = "dev.kcoap.sample.jvm.MainKt"
}

// A Maven build gets JVM classes only from the JVM artifacts (kcoap-coap-jvm). A kcoap POM that names a
// multiplatform root artifact (kcoap-common instead of kcoap-common-jvm) puts that artifact's jar, which holds
// common metadata and no JVM classes, on the classpath instead; this names it, where compilation or the run would
// fail later with a missing class, or not at all when nothing loads one.
val verifyJvmArtifactsOnly = tasks.register("verifyJvmArtifactsOnly") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when a kcoap artifact on the runtime classpath is not a JVM artifact."
    val artifacts = configurations.runtimeClasspath.flatMap { it.incoming.artifacts.resolvedArtifacts }
    doLast {
        val kcoap = artifacts.get().map { it.id.componentIdentifier }
            .filterIsInstance<ModuleComponentIdentifier>()
            .filter { it.group == "dev.kcoap" }
        val notJvm = kcoap.filterNot { it.module.endsWith("-jvm") }
        if (notJvm.isNotEmpty()) {
            throw GradleException("kcoap artifacts without JVM classes on the runtime classpath: ${notJvm.joinToString()}")
        }
        if (kcoap.none { it.module == "kcoap-coap-jvm" }) throw GradleException("No kcoap module resolved: $kcoap")
        logger.lifecycle("All ${kcoap.size} kcoap artifacts on the runtime classpath, read from their POMs, are JVM artifacts")
    }
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyJvmArtifactsOnly)
}

// The WebSocket server runs on the JVM and natively; browsers cannot accept connections.

plugins {
    id("kcoap.kmp-jvm-native-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport-ktor-websocket"))
            // Applications that run a ktor server mount the CoAP WebSocket endpoint in their own routing;
            // KtorWebSocketListener.start takes the engine's ApplicationEngineFactory (ktor-server-core).
            // No engine is a dependency: the application brings the one it runs, such as ktor-server-cio,
            // and so do the tests.
            api(libs.ktor.server.core)
            api(libs.ktor.server.websockets)
            // ConnectionListener, Endpoint and LoggerFactory appear in the public API.
            api(project(":kcoap-transport"))
            api(project(":kcoap-common"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The application brings the ktor server engine it runs (WP-W1), so no engine may reach what this module
// publishes. verifyNoServerEngine, part of check and of `scripts/test unit`, reads every Gradle module
// file and POM this module would publish, and the JVM runtime classpath for an engine arriving through
// another module. The tests bring CIO themselves, so without it nothing would notice an engine slipping
// back into commonMain.
val verifyNoServerEngine = tasks.register("verifyNoServerEngine") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when a ktor server engine is among the dependencies this module publishes."
    val engines = Regex("""\bktor-server-(cio|netty|jetty|jetty-jakarta|tomcat|tomcat-jakarta)(-[a-z0-9]+)?\b""")
    val published = files(tasks.withType<GenerateModuleMetadata>(), tasks.withType<GenerateMavenPom>())
    val runtime = configurations.named("jvmRuntimeClasspath").flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { artifacts -> artifacts.map { it.id.componentIdentifier.displayName }.sorted() }
    inputs.files(published).withPropertyName("published")
    inputs.property("jvmRuntimeClasspath", runtime)
    doLast {
        val files = published.files.sortedBy { it.path }
        // A module file and a POM per publication: none means the publications were not found.
        check(files.any { it.name == "module.json" } && files.any { it.name == "pom-default.xml" }) {
            "verifyNoServerEngine found no publications to read: $files"
        }
        val found = files.flatMap { file ->
            engines.findAll(file.readText()).map { "${file.parentFile.name}/${file.name}: ${it.value}" }.distinct()
        } + runtime.get().filter { engines.containsMatchIn(it) }.map { "jvmRuntimeClasspath: $it" }
        if (found.isNotEmpty()) {
            throw GradleException(
                "kcoap-transport-ktor-websocket-server must not bring a ktor server engine; the application " +
                    "chooses one and passes it to KtorWebSocketListener.start. Found:\n  " + found.joinToString("\n  "),
            )
        }
    }
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyNoServerEngine)
}

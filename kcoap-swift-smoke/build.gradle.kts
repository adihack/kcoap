// The Swift smoke test (docs/target-architecture.md WP-D2): a framework of kcoap-coap for the Apple targets, and
// src/swift/SwiftSmoke.swift, which the macOS CI job compiles against it and runs (scripts/test swift). It shows
// that Swift catches kcoap's failures as NSError instead of crashing, and builds a configuration with the
// builders. Unpublished, Apple targets only, so it takes no tier plugin:
//
//   ./gradlew :kcoap-swift-smoke:swiftSmoke   -> links the macOS framework, compiles the script, runs it
//
// On Linux the Apple klibs cross-compile, but the framework links only on macOS, and swiftSmoke is skipped.

import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType

plugins {
    id("kcoap.unpublished")
    id("org.jetbrains.kotlin.multiplatform")
    // The root project's coverage report takes every module (kcoap.coverage); with no JVM target this one adds
    // nothing to it.
    id("org.jetbrains.kotlinx.kover")
}

kotlin {
    explicitApi()

    val appleTargets = listOf(macosArm64(), iosArm64(), iosSimulatorArm64())
    appleTargets.forEach { target ->
        target.binaries.framework {
            baseName = "KcoapSmoke"
            binaryOption("bundleId", "dev.kcoap.swiftsmoke")
            // The API the script uses: CoapClient, its configuration and CoapException (kcoap-coap), CoapUri and
            // CoapRequest (kcoap-coap-message), KcoapException (kcoap-common). Types of other modules in their
            // signatures come along without the rest of those modules.
            export(project(":kcoap-coap"))
            export(project(":kcoap-coap-message"))
            export(project(":kcoap-common"))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-coap"))
            api(project(":kcoap-coap-message"))
            api(project(":kcoap-common"))
            implementation(project(":kcoap-testkit"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The macOS framework the script links against: the CI runner is an arm64 Mac.
val smokeFramework = kotlin.macosArm64().binaries.getFramework(NativeBuildType.DEBUG)
val swiftSource = layout.projectDirectory.file("src/swift/SwiftSmoke.swift")
val swiftExecutable = layout.buildDirectory.file("swift-smoke/SwiftSmoke")

val buildSwiftSmoke = tasks.register<Exec>("buildSwiftSmoke") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Compiles src/swift/SwiftSmoke.swift against the macOS framework (macOS only)."
    val onMac = System.getProperty("os.name").startsWith("Mac")
    onlyIf("the framework links only on macOS") { onMac }
    dependsOn(smokeFramework.linkTaskProvider)
    // The directory that holds KcoapSmoke.framework, from the link task: the -F search path, and the rpath
    // that finds the framework when the script runs.
    val frameworks = smokeFramework.outputDirectory
    val executable = swiftExecutable.get().asFile
    inputs.file(swiftSource)
    inputs.dir(frameworks)
    outputs.file(executable)
    commandLine(
        "xcrun", "swiftc",
        "-parse-as-library",
        "-swift-version", "5",
        "-module-name", "KcoapSwiftSmoke",
        "-F", frameworks.absolutePath,
        "-framework", "KcoapSmoke",
        "-Xlinker", "-rpath", "-Xlinker", frameworks.absolutePath,
        "-o", executable.absolutePath,
        swiftSource.asFile.absolutePath,
    )
    doFirst { executable.parentFile.mkdirs() }
}

tasks.register<Exec>("swiftSmoke") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs the Swift smoke test against the macOS framework (macOS only); fails on any mismatch."
    val onMac = System.getProperty("os.name").startsWith("Mac")
    onlyIf("the framework links only on macOS") { onMac }
    dependsOn(buildSwiftSmoke)
    commandLine(swiftExecutable.get().asFile.absolutePath)
}

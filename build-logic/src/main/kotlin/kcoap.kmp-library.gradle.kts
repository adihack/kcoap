// Every kcoap library module that runs on all platforms.
//
// Targets:
//  - jvm: server-side JVM and Android. The protocol code is pure Kotlin and the default crypto backend
//    goes through JCA, so a separate Android target is not needed yet.
//  - iosArm64 / iosSimulatorArm64: the iOS deliverable (XCFramework later).
//  - macosArm64: lets CI run native tests on a macOS host without booting a simulator.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
    id("kcoap.publishing")
}

configureKcoapLibrary(KcoapTargets.ALL)

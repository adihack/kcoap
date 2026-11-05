// Tier UNIVERSAL: every kcoap module that runs on all platforms, that is the core modules and the
// adapters whose library builds for every target (docs/target-architecture.md §2.1).
//
// Targets:
//  - jvm: server-side JVM and Android. The protocol code is pure Kotlin and the default crypto backend
//    goes through JCA, so a separate Android target is not needed yet.
//  - iosArm64 / iosSimulatorArm64: the iOS deliverable (XCFramework later).
//  - macosArm64: lets CI run native tests on a macOS host without booting a simulator.
//  - linuxX64: Linux gateways; runs the common tests as native code on Linux CI.
//  - js / wasmJs: browsers and Node.js, with WebCrypto under the default crypto backend.
//
// The module is published unless kcoap.unpublished comes first.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
}

configureKcoapLibrary(KcoapTargets.UNIVERSAL)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Shared configuration for every kcoap library module.
//
// Targets:
//  - jvm: used by both server-side JVM and Android consumers. The protocol code is pure Kotlin and the
//    default crypto backend goes through JCA, so a separate Android target is not needed yet.
//  - iosArm64 / iosSimulatorArm64: the iOS deliverable (XCFramework later).
//  - macosArm64: lets CI run native tests on a macOS host without booting a simulator.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

group = providers.gradleProperty("kcoap.group").get()
version = providers.gradleProperty("kcoap.version").get()

kotlin {
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    iosArm64()
    iosSimulatorArm64()
    macosArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

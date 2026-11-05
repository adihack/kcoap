// A kcoap module that only runs on the JVM, because it wraps a JVM library (Bouncy Castle, Netty).
// It stays a multiplatform module so its sources and tests follow the same layout as the others.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
    id("kcoap.publishing")
}

configureKcoapLibrary(KcoapTargets.JVM)

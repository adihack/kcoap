// Tier JVM_LIBRARY: a kcoap module that only runs on the JVM (servers and Android), because it wraps a
// JVM library: Bouncy Castle today, a JVM TLS listener later. It stays a multiplatform module so its
// sources and tests follow the same layout as the others.
//
// The module is published, with ABI dumps of its public API, unless kcoap.unpublished comes first.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
}

configureKcoapLibrary(KcoapTargets.JVM_LIBRARY)

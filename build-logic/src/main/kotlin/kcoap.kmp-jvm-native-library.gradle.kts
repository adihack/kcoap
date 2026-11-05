// Tier SOCKETS: a kcoap adapter module for the JVM and the native targets but not the web, because the
// library it wraps opens sockets, which browsers do not offer (ktor-network for UDP and TCP, a ktor
// server engine). ktor-network also reaches UDP and TCP on Node.js through node:dgram and node:net, but
// one js artifact serves browsers and Node.js alike, so this tier never includes js.
//
// The module is published unless kcoap.unpublished comes first.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
}

configureKcoapLibrary(KcoapTargets.SOCKETS)

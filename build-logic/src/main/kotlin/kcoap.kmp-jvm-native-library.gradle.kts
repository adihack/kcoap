// A kcoap adapter module for the JVM and the native targets but not the web, because the library it
// wraps opens sockets, which browsers do not offer (ktor-network for UDP). ktor-network also reaches UDP
// and TCP on Node.js through node:dgram and node:net, so a Node.js target is possible later; browsers
// would still need WebSockets.

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlinx.kover")
}

configureKcoapLibrary(KcoapTargets.NATIVE_AND_JVM)

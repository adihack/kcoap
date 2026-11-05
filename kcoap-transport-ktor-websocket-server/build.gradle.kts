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

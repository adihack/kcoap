plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-coap"))
            api(project(":kcoap-cose"))
            api(project(":kcoap-crypto"))
            // Endpoint and LoggerFactory appear in the public API.
            api(project(":kcoap-transport"))
            api(project(":kcoap-common"))
            implementation(project(":kcoap-cbor"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
            implementation(project(":kcoap-crypto-default"))
        }
        jvmTest.dependencies {
            // The libcoap interop tests run OSCORE through the CoAP client and server over real UDP, TCP and
            // WebSockets.
            implementation(project(":kcoap-transport-ktor"))
            implementation(project(":kcoap-transport-ktor-websocket"))
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            // The engine of the WebSocket listener, which the server module leaves to the application.
            implementation(libs.ktor.server.cio)
        }
    }
}

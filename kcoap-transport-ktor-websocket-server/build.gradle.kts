plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport-ktor-websocket"))
            // Applications that run a ktor server mount the CoAP WebSocket endpoint in their own routing.
            api(libs.ktor.server.core)
            api(libs.ktor.server.websockets)
            implementation(libs.ktor.server.cio)
            implementation(project(":kcoap-common"))
        }
    }
}

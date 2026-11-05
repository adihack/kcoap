plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport"))
            api(libs.kotlinx.coroutines.core)
            // Applications may pass their own HttpClient, with the engine of their platform; the WebSockets
            // plugin is part of ktor-client-core.
            api(libs.ktor.client.core)
            // LoggerFactory appears in the public constructors.
            api(project(":kcoap-common"))
        }
        // The engine behind the default client; browsers bring their own (the JS engine).
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        nativeMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            // Loopback tests against the server adapter and plain ktor servers.
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            implementation(libs.ktor.server.cio)
        }
    }
}

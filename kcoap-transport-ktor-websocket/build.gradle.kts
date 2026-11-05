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
        // The engine behind the default client. On Apple it is Darwin: URLSession brings up the cellular
        // radio and honours VPN On Demand, which CIO's BSD sockets do not. Browsers and Node.js bring their
        // own WebSocket (the JS engine, in ktor-client-core).
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            // Loopback tests against the server adapter and plain ktor servers.
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            implementation(libs.ktor.server.cio)
        }
        nativeTest.dependencies {
            // The default client (Darwin on Apple, CIO on Linux) against the server adapter on CIO.
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            implementation(libs.ktor.server.cio)
        }
    }
}

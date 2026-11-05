plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-common"))
            api(project(":kcoap-transport"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
        }
        jvmTest.dependencies {
            // The Content-Format 272 codec is checked against kcoap-cbor here only: with kcoap-cbor on the
            // common test classpath, Kotlin/Wasm 2.4.21 emits an invalid module (ConcurrencyStressTest).
            implementation(project(":kcoap-cbor"))
            // The libcoap interop tests talk to coap-server and coap-client over real UDP, TCP and
            // WebSocket sockets.
            implementation(project(":kcoap-transport-ktor"))
            implementation(project(":kcoap-transport-ktor-websocket"))
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            // The engine of the WebSocket listener, which the server module leaves to the application.
            implementation(libs.ktor.server.cio)
        }
    }
}

tasks.withType<Test>().configureEach {
    // The libcoap interop tests run or skip depending on docker, which Gradle cannot see as an input,
    // so a result from one environment must never be reused in another.
    inputs.property("libcoapImage", providers.environmentVariable("KCOAP_LIBCOAP_IMAGE").orElse("kcoap/libcoap:v4.3.5b"))
    outputs.cacheIf { false }
}

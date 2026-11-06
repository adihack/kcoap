plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-edhoc"))
            api(project(":kcoap-oscore"))
            // CoapClient and CoapResource (kcoap-coap), CoapRequest and the message-security SPI
            // (kcoap-coap-message), CryptoBackend, Endpoint and LoggerFactory appear in the public API.
            api(project(":kcoap-coap"))
            api(project(":kcoap-coap-message"))
            api(project(":kcoap-crypto"))
            api(project(":kcoap-transport"))
            api(project(":kcoap-common"))
            implementation(project(":kcoap-cbor"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kcoap-crypto-default"))
            implementation(project(":kcoap-testkit"))
        }
        jvmTest.dependencies {
            // The browser-topology interop (WP-I1): a WebSocket client and a UDP device with libcoap's proxy between
            // them. Post-quantum EDHOC cannot cross that proxy yet (docs/adr/0009-browser-topology-interop.md).
            implementation(project(":kcoap-transport-ktor"))
            implementation(project(":kcoap-transport-ktor-websocket"))
            // docs/getting-started.md's post-quantum backend, compiled by GettingStartedExamplesTest.
            implementation(project(":kcoap-crypto-bc"))
        }
    }
}

tasks.withType<Test>().configureEach {
    // The browser-topology interop runs or skips depending on docker, which Gradle cannot see as an input, so a
    // result from one environment must never be reused in another.
    inputs.property("libcoapImage", providers.environmentVariable("KCOAP_LIBCOAP_IMAGE").orElse("kcoap/libcoap:v4.3.5b"))
    outputs.cacheIf { false }
}

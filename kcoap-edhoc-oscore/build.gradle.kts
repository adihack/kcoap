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
            // For WP-I1 (docs/target-architecture.md §4.3), which adds the first jvmTest here: the combined
            // EDHOC + OSCORE flow end to end over real sockets, with real ML-KEM / ML-DSA.
            implementation(project(":kcoap-transport-ktor"))
            implementation(project(":kcoap-crypto-bc"))
        }
    }
}

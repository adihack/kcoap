plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // ByteArrayBuilder (in OptionCodec's signature) and the opt-in markers come from kcoap-common;
            // Endpoint (message security) and CoapScheme (CoapUri) from kcoap-transport. No coroutines: the wire
            // vocabulary does no I/O and has no concurrency of its own.
            api(project(":kcoap-common"))
            api(project(":kcoap-transport"))
        }
        commonTest.dependencies {
            // ByteMutator, for the codec fuzz test.
            implementation(project(":kcoap-testkit"))
        }
    }
}

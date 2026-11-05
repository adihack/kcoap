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
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
            implementation(project(":kcoap-crypto-default"))
        }
        jvmTest.dependencies {
            // The libcoap interop tests run OSCORE through the CoAP client and server over real UDP.
            implementation(project(":kcoap-transport-ktor"))
        }
    }
}

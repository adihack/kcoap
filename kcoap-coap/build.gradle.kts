plugins {
    id("kcoap.kmp-library")
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
            // The libcoap interop tests talk to coap-server and coap-client over real UDP sockets.
            implementation(project(":kcoap-transport-ktor"))
        }
    }
}

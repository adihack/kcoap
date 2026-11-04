plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-edhoc"))
            api(project(":kcoap-oscore"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(project(":kcoap-crypto-default"))
            implementation(project(":kcoap-testkit"))
        }
        jvmTest.dependencies {
            // Real sockets for the libcoap and libedhoc interop tests, and real ML-KEM / ML-DSA.
            implementation(project(":kcoap-transport-ktor"))
            implementation(project(":kcoap-crypto-bc"))
        }
    }
}

plugins {
    id("kcoap.kmp-library")
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
            implementation(project(":kcoap-crypto-default"))
        }
    }
}

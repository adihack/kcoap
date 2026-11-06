plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-cbor"))
            api(project(":kcoap-crypto"))
            implementation(project(":kcoap-common"))
        }
    }
}

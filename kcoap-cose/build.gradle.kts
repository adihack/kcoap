plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-cbor"))
            api(project(":kcoap-crypto"))
        }
    }
}

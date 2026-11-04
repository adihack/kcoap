plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-edhoc"))
            api(project(":kcoap-oscore"))
        }
    }
}

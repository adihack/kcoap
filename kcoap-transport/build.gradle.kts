plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // The opt-in markers (ExperimentalKcoapApi on DatagramSecurity), and through this module for
            // every module above it. kcoap-common itself has no dependencies.
            api(project(":kcoap-common"))
        }
    }
}

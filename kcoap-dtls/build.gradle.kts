// Built and tested like the other modules, but not published until the DTLS engine exists: its SPIs
// would otherwise ship with no implementation (docs/target-architecture.md §2.1).

plugins {
    id("kcoap.unpublished")
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport"))
            api(project(":kcoap-crypto"))
        }
    }
}

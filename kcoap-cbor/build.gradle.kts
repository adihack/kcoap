plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api although no kcoap-common type is in the public API yet: CborException will extend
            // KcoapException (docs/target-architecture.md §3.6, WP-D2).
            api(project(":kcoap-common"))
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
        }
    }
}

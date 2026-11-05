plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api although no kcoap-common type is in the public API yet: CertificateFormatException will
            // extend KcoapException (docs/target-architecture.md §3.6, WP-A4 and WP-D2).
            api(project(":kcoap-common"))
        }
        jvmTest.dependencies {
            // Independent SHAKE256 and KMAC256 for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

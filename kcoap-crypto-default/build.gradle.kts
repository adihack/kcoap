plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-crypto"))
            api(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        jvmTest.dependencies {
            // Independent CCM/GCM/ChaCha20 implementation for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

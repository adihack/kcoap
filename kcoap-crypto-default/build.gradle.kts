plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-crypto"))
            api(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            // The Mutex of the capability memo.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // Stress.run and Stress.race for the memo under real concurrency.
            implementation(project(":kcoap-testkit"))
        }
        jvmTest.dependencies {
            // Independent CCM/GCM/ChaCha20 implementation for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

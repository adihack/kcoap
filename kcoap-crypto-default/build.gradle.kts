plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // CryptoBackend, the supertype of DefaultCryptoBackend, and the types of its members.
            api(project(":kcoap-crypto"))
            // implementation: no cryptography-kotlin type is in this module's public API (the provider
            // constructor is internal), so consumers do not compile against cryptography-kotlin.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            // The Mutex of the capability memo.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // hexToBytes and toHex for the vectors; the main code imports nothing from kcoap-common.
            implementation(project(":kcoap-common"))
            // Stress.run and Stress.race for the memo under real concurrency.
            implementation(project(":kcoap-testkit"))
        }
        jvmTest.dependencies {
            // Independent CCM/GCM/ChaCha20 implementation for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

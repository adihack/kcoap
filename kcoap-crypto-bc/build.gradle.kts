plugins {
    id("kcoap.kmp-jvm-library")
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // CryptoBackend: the supertype and the classical constructor parameter. The application chooses
            // the classical backend, so this module does not depend on kcoap-crypto-default.
            api(project(":kcoap-crypto"))
            // wipe for the drawn randomness and the key copies.
            implementation(project(":kcoap-common"))
            // ML-KEM (FIPS 203) and ML-DSA (FIPS 204) through the lightweight API; no JCA provider is registered.
            implementation(libs.bouncycastle.prov)
        }
        jvmTest.dependencies {
            // The classical backend the tests wrap, as an application does.
            implementation(project(":kcoap-crypto-default"))
        }
    }
}

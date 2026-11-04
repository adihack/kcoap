plugins {
    id("kcoap.kmp-jvm-library")
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // The classical algorithms come from the default backend, which the BC backend wraps.
            api(project(":kcoap-crypto-default"))
            // ML-KEM (FIPS 203) and ML-DSA (FIPS 204) through the lightweight API; no JCA provider is registered.
            implementation(libs.bouncycastle.prov)
        }
    }
}

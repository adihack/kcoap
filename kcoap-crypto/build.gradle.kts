plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-common"))
        }
        jvmTest.dependencies {
            // Independent SHAKE256 and KMAC256 for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

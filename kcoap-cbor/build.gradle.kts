plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-common"))
        }
        commonTest.dependencies {
            implementation(project(":kcoap-testkit"))
        }
    }
}

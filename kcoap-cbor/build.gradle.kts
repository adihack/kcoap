plugins {
    id("kcoap.kmp-library")
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

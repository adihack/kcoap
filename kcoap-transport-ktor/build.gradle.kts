plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport"))
            api(libs.kotlinx.coroutines.core)
            implementation(project(":kcoap-common"))
            implementation(libs.ktor.network)
        }
    }
}

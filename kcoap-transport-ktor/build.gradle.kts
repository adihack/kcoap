plugins {
    id("kcoap.kmp-jvm-native-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport"))
            api(libs.kotlinx.coroutines.core)
            // LoggerFactory and ktor's SelectorManager appear in the public bind() and constructors.
            api(project(":kcoap-common"))
            api(libs.ktor.network)
        }
    }
}

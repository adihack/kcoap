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
        // KtorTlsConnector (coaps+tcp) exists on the JVM only: ktor-network-tls 3.6.1 runs a TLS client there
        // and nowhere else (docs/platforms.md). Its types stay out of the public API, which uses the JDK's.
        jvmMain.dependencies {
            implementation(libs.ktor.network.tls)
        }
        // CioWebSocketNameCheckTest pins how ktor's CIO client, on the same ktor-network-tls, checks names: why the
        // WebSocket connector's default client serves no coaps+ws on the JVM.
        jvmTest.dependencies {
            implementation(project(":kcoap-transport-ktor-websocket"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.cio)
        }
        // NativeTlsUnavailableTest pins that ktor-network-tls has no TLS on the native targets, so its arrival
        // there is noticed.
        nativeTest.dependencies {
            implementation(libs.ktor.network.tls)
        }
    }
}

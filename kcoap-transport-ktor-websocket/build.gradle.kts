import org.jetbrains.kotlin.gradle.tasks.IncrementalSyncTask

plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":kcoap-transport"))
            api(libs.kotlinx.coroutines.core)
            // Applications may pass their own HttpClient, with the engine of their platform; the WebSockets
            // plugin is part of ktor-client-core.
            api(libs.ktor.client.core)
            // LoggerFactory appears in the public constructors.
            api(project(":kcoap-common"))
        }
        // The engine behind the default client, which each platform's defaultClient() names rather than
        // leaving it to ktor's lookup. On Apple it is Darwin: URLSession brings up the cellular radio and
        // honours VPN On Demand, which CIO's BSD sockets do not. Browsers and Node.js bring their own
        // WebSocket (the Js engine, in ktor-client-core).
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        appleMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        linuxMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            // Loopback tests against the server adapter and plain ktor servers.
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            implementation(libs.ktor.server.cio)
            // A CoAP client on the default connector, which must refuse coaps+ws where its engine does not
            // authenticate wss servers (DefaultConnectorCoapsWsTest).
            implementation(project(":kcoap-coap"))
        }
        linuxTest.dependencies {
            // The same refusal on Linux native, where CIO has no TLS (LinuxSecureWebSocketTest).
            implementation(project(":kcoap-coap"))
        }
        nativeTest.dependencies {
            // The default client (Darwin on Apple, CIO on Linux) against the server adapter on CIO.
            implementation(project(":kcoap-transport-ktor-websocket-server"))
            implementation(libs.ktor.server.cio)
        }
        appleTest.dependencies {
            // CIO linked next to Darwin, as in an app that uses it for its own HTTP calls: DefaultEngineTest
            // shows the default client stays on Darwin.
            implementation(libs.ktor.client.cio)
        }
        jsTest.dependencies {
            // The Node.js tests run an npm ws server, whose handleProtocols sees the subprotocols offered.
            implementation(npm("ws", libs.versions.npm.ws.get()))
        }
    }
}

// -Pkcoap.browser=<browser command> (google-chrome, chromium or firefox) runs this module's js tests in that
// browser, headless, against a JVM listener: BrowserAgainstJvmListenerTest in jvmTest, which skips without
// the property. `scripts/test browser` and CI's browser job (Chrome) set it.
providers.gradleProperty("kcoap.browser").orNull?.let { browser ->
    val jsTests = tasks.named<IncrementalSyncTask>("jsTestTestDevelopmentExecutableCompileSync")
    val jsTestFiles = jsTests.flatMap { it.destinationDirectory }
    // The test executable's own module; its dependencies sit next to it.
    val jsTestModule = "${rootProject.name}-${project.name}-test.js"
    val mocha = rootProject.layout.buildDirectory.file("js/node_modules/mocha/mocha.js")
    val work = layout.buildDirectory.dir("browser")
    tasks.named<Test>("jvmTest") {
        dependsOn(jsTests, ":kotlinNpmInstall")
        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf(
                    "-Dkcoap.browser=$browser",
                    "-Dkcoap.browser.tests=${jsTestFiles.get().path}",
                    "-Dkcoap.browser.module=$jsTestModule",
                    "-Dkcoap.browser.mocha=${mocha.get().asFile.path}",
                    "-Dkcoap.browser.work=${work.get().asFile.path}",
                )
            },
        )
        // The result depends on the browser installed, which Gradle cannot see.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
}

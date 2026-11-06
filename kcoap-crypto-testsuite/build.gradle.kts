// The conformance suites every CryptoBackend's tests extend. UNIVERSAL: the suites are common code, and
// kotlin-test and kotlinx-coroutines-test build for every target, so a backend runs them wherever it runs.
// kcoap.core keeps the main code in commonMain (verifyCommonOnly), so the suites cannot drift per target.
plugins {
    id("kcoap.kmp-library")
    id("kcoap.core")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // CryptoBackend and the algorithm types: the subject of every suite and in its signatures.
            api(project(":kcoap-crypto"))
            // The suites are tests: @Test on their members, and the assertions their subclasses see fail.
            api(kotlin("test"))
            // runTest, whose TestResult every test member returns.
            api(libs.kotlinx.coroutines.test)
            // hexToBytes for the known answers and the NIST vectors.
            implementation(project(":kcoap-common"))
        }
        jvmMain.dependencies {
            // kotlin-test's @Test on the JVM, where Gradle infers the framework only for test compilations: JUnit 5,
            // the platform kcoap's tests run on (useJUnitPlatform), so a backend's jvmTest runs the suites there too.
            api(kotlin("test-junit5"))
        }
        commonTest.dependencies {
            // A real backend for the suites' own tests to wrap in faulty decorators.
            implementation(project(":kcoap-crypto-default"))
        }
    }
}

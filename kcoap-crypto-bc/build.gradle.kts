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
            // The conformance suites: the capability contract, and NIST's ACVP vectors for ML-KEM and ML-DSA.
            implementation(project(":kcoap-crypto-testsuite"))
        }
    }
}

// The conformance suite prints a capability report (kcoap-crypto-testsuite). Its lines go to the build log, so a
// CI log shows what the backend runs.
// The report reaches the log only while the tests run, and it depends on the platform's cryptography, which
// Gradle cannot see as an input. So the test tasks are never restored from the build cache (which CI's
// setup-gradle brings from main) nor skipped as up to date: every build that asks for them runs them on its own
// machine and logs that machine's report.
tasks.withType<AbstractTestTask>().configureEach {
    outputs.cacheIf("the capability report must reach the build log") { false }
    outputs.upToDateWhen { false }
    val target = name
    val log = logger
    addTestOutputListener { _, output ->
        output.message.lineSequence()
            .filter { it.startsWith("kcoap-crypto capabilities") }
            .forEach { log.lifecycle("[$target] $it") }
    }
}

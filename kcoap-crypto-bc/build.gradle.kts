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
tasks.withType<AbstractTestTask>().configureEach {
    val target = name
    val log = logger
    addTestOutputListener { _, output ->
        output.message.lineSequence()
            .filter { it.startsWith("kcoap-crypto capabilities") }
            .forEach { log.lifecycle("[$target] $it") }
    }
}

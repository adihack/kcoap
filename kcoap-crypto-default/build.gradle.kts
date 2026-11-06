plugins {
    id("kcoap.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // CryptoBackend, the supertype of DefaultCryptoBackend, and the types of its members.
            api(project(":kcoap-crypto"))
            // implementation: no cryptography-kotlin type is in this module's public API (the provider
            // constructor is internal), so consumers do not compile against cryptography-kotlin.
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
            // wipe for the raw key copies.
            implementation(project(":kcoap-common"))
            // The Mutex of the capability memo.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // hexToBytes and toHex for the vectors.
            implementation(project(":kcoap-common"))
            // Stress.run and Stress.race for the memo under real concurrency.
            implementation(project(":kcoap-testkit"))
            // The conformance suite: the capability contract on every target, and its report.
            implementation(project(":kcoap-crypto-testsuite"))
        }
        jvmTest.dependencies {
            // Independent CCM/GCM/ChaCha20 implementation for differential tests.
            implementation(libs.bouncycastle.prov)
        }
    }
}

// The conformance suite prints a capability report on every target (kcoap-crypto-testsuite). Its lines go to
// the build log, so a CI log shows what the backend runs on each platform.
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

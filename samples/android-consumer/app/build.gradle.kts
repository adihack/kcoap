import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// The kcoap version to use: -Pkcoap.version, as publishToMavenLocal was given it, or else the one in the
// repository's gradle.properties, which a plain publishToMavenLocal publishes.
val kcoapVersion: String = providers.gradleProperty("kcoap.version").orElse(
    providers.fileContents(layout.settingsDirectory.file("../../gradle.properties")).asText.map { text ->
        Properties().apply { load(text.reader()) }.getProperty("kcoap.version")
    },
).get()

android {
    namespace = "dev.kcoap.sample.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kcoap.sample.android"
        // kcoap's minSdk (android-minSdk in its version catalog), which checkAndroidApi checks its jars against.
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    // The two ways an app sets up kcoap's cryptography, each built and shrunk on its own: the platform's
    // providers alone, or with the post-quantum backend on Bouncy Castle as well.
    flavorDimensions += "crypto"
    productFlavors {
        create("classical") {
            dimension = "crypto"
        }
        create("postQuantum") {
            dimension = "crypto"
            applicationIdSuffix = ".pq"
        }
    }

    buildTypes {
        release {
            // R8 shrinks and obfuscates kcoap and its dependencies as in a production app, so a missing class
            // or a missing keep rule fails this build instead of an app. proguard-rules.pro stays empty: a
            // rule kcoap needs belongs in kcoap's own jars, where every app gets it.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key, so the shrunk APK installs on an emulator (PL-2). Never published.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // kcoap's jvm classes are Java 17 bytecode, and Kotlin inlines kcoap's inline functions only into
    // bytecode of the same target or newer.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // InvalidPackage flags every reference a library jar makes to a Java package Android lacks, used or
        // not: ktor-utils' java.lang.management, Bouncy Castle's javax.naming. Each is triaged in README.md
        // ("API level findings"); R8 fails the release build if a reachable one goes missing, and
        // androidApiDependencyReport lists them all. Reported, then, but not fatal.
        warning += "InvalidPackage"
    }
}

dependencies {
    implementation("dev.kcoap:kcoap-coap:$kcoapVersion")
    implementation("dev.kcoap:kcoap-transport-ktor:$kcoapVersion")
    implementation("dev.kcoap:kcoap-oscore:$kcoapVersion")
    implementation("dev.kcoap:kcoap-edhoc:$kcoapVersion")
    implementation("dev.kcoap:kcoap-cose:$kcoapVersion")
    implementation("dev.kcoap:kcoap-crypto:$kcoapVersion")
    implementation("dev.kcoap:kcoap-crypto-default:$kcoapVersion")
    "postQuantumImplementation"("dev.kcoap:kcoap-crypto-bc:$kcoapVersion")
}

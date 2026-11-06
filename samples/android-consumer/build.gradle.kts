plugins {
    alias(libs.plugins.android.application) apply false
    // The Android Gradle plugin compiles Kotlin itself (built-in Kotlin), with the Kotlin Gradle plugin it
    // finds on the build's classpath. This puts the one kcoap is built with there, instead of the older one
    // AGP depends on, whose compiler cannot read the metadata of kcoap's classes.
    alias(libs.plugins.kotlin.android) apply false
}

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.maven.publish.plugin)
    implementation(libs.dokka.gradle.plugin)
}

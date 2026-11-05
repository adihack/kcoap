plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kover.gradle.plugin)
    implementation(libs.maven.publish.plugin)
    implementation(libs.dokka.gradle.plugin)

    // Gradle TestKit (added by kotlin-dsl) runs the convention plugins on fixture projects.
    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // The fixtures read the repository's version catalog, as kcoap's own modules do.
    val catalog = layout.projectDirectory.file("../gradle/libs.versions.toml")
    inputs.file(catalog).withPathSensitivity(PathSensitivity.NONE).withPropertyName("versionCatalog")
    systemProperty("kcoap.versionCatalog", catalog.asFile.absolutePath)
    // TestKit's own Gradle home, kept between runs so the fixture builds do not download again.
    systemProperty("kcoap.testKitDir", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}

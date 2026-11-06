// The bill of materials: a Java platform that constrains every published kcoap module to one version, so a
// consumer writes the version once (docs/getting-started.md):
//
//   implementation(platform("dev.kcoap:kcoap-bom:<version>"))
//   implementation("dev.kcoap:kcoap-coap")
//
// The constraints are not typed by hand. Every module that kcoap.publishing publishes (every module with a
// tier plugin, except those marked kcoap.unpublished) adds its multiplatform and JVM artifacts
// (bomArtifactIds, KcoapBom.kt) when that plugin is applied, so a new module joins the BOM by itself and an
// unpublished one never does. The BOM is published like the other modules (KcoapPublishing.kt).
//
// Each of those modules also depends on the BOM as a platform (dependOnBom, KcoapBom.kt): Gradle's version
// alignment, so that when another library brings one kcoap module at a higher version, the BOM at that
// version comes with it and lifts the other kcoap modules too.
//
//   ./gradlew :kcoap-bom:verifyBomConstraints   -> the published POM and module metadata constrain exactly
//                                                  the published modules (part of check and scripts/test unit)

import com.vanniktech.maven.publish.JavaPlatform
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    base
    `java-platform`
    id("com.vanniktech.maven.publish")
}

group = kcoapGroup()
version = kcoapVersion()

configureKcoapMavenPublishing(JavaPlatform())

val bom = project
val kcoapGroup = kcoapGroup()
val kcoapVersion = kcoapVersion()

// What the publications say the build publishes, gathered independently of the constraints below, for the
// check: the multiplatform module and the JVM artifact of every project with a Maven publication.
val publishedArtifacts = objects.setProperty<String>()

rootProject.subprojects {
    val module = this
    if (module == bom) return@subprojects
    pluginManager.withPlugin("kcoap.publishing") {
        bom.dependencies.constraints {
            bomArtifactIds(module.name).forEach { artifact -> add("api", "$kcoapGroup:$artifact:$kcoapVersion") }
        }
        module.dependOnBom(bom)
    }
    pluginManager.withPlugin("maven-publish") {
        module.extensions.getByType<PublishingExtension>().publications.withType<MavenPublication>()
            .matching { it.name == "kotlinMultiplatform" || it.name == "jvm" }
            .all {
                val publication = this
                publishedArtifacts.add(provider { "${publication.groupId}:${publication.artifactId}:${publication.version}" })
            }
    }
}

val verifyBomConstraints = tasks.register<VerifyBomConstraints>("verifyBomConstraints") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the BOM constrains exactly the published kcoap modules."
    bomName.set("$kcoapGroup:${project.name}:$kcoapVersion")
    pom.set(layout.buildDirectory.file("publications/maven/pom-default.xml"))
    moduleMetadata.set(layout.buildDirectory.file("publications/maven/module.json"))
    expected.set(publishedArtifacts)
    report.set(layout.buildDirectory.file("bom/constraints.txt"))
    dependsOn("generatePomFileForMavenPublication", "generateMetadataFileForMavenPublication")
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyBomConstraints)
}

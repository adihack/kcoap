// Publishing and API documentation for every kcoap library module.
//
//   ./gradlew publishToMavenLocal                              -> ~/.m2, for trying a build locally
//   ./gradlew publishAllPublicationsToGitHubPackagesRepository  -> GitHub Packages (CI, on release tags)
//   ./gradlew publishToMavenCentral                            -> Maven Central (CI, when the secrets exist)
//
// Coordinates come from gradle.properties (kcoap.group, kcoap.version); the release workflow sets the
// version from the tag. Signing is enabled when a key is provided (ORG_GRADLE_PROJECT_signingInMemoryKey).

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

val repository = providers.gradleProperty("kcoap.repository").getOrElse("adihack/kcoap")

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = true))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
    coordinates(group.toString(), project.name, version.toString())

    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "kcoap: CoAP, OSCORE and EDHOC for Kotlin Multiplatform (${project.name})" })
        url.set("https://github.com/$repository")
        inceptionYear.set("2026")
        // Maven Central requires a license; set kcoap.license.name and kcoap.license.url once one is chosen.
        val licenseName = providers.gradleProperty("kcoap.license.name")
        if (licenseName.isPresent) {
            licenses {
                license {
                    name.set(licenseName)
                    url.set(providers.gradleProperty("kcoap.license.url"))
                }
            }
        }
        developers {
            developer {
                id.set("adihack")
                name.set("Adrian Soltys")
                url.set("https://github.com/adihack")
            }
        }
        scm {
            url.set("https://github.com/$repository")
            connection.set("scm:git:https://github.com/$repository.git")
            developerConnection.set("scm:git:ssh://git@github.com/$repository.git")
        }
    }
}

publishing {
    repositories {
        // Credentials: ORG_GRADLE_PROJECT_GitHubPackagesUsername / ORG_GRADLE_PROJECT_GitHubPackagesPassword.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/$repository")
            credentials(PasswordCredentials::class)
        }
    }
}

dokka {
    moduleName.set(project.name)
    dokkaSourceSets.configureEach {
        includes.from(layout.projectDirectory.file("README.md").asFile.takeIf { it.exists() } ?: emptyList<File>())
        sourceLink {
            localDirectory.set(layout.projectDirectory.dir("src"))
            remoteUrl("https://github.com/$repository/tree/main/${project.name}/src")
            remoteLineSuffix.set("#L")
        }
    }
}

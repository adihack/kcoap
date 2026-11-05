// Publishing and API documentation for every kcoap library module, applied by the tier plugins unless
// the module applies kcoap.unpublished first.
//
//   ./gradlew publishToMavenLocal                              -> ~/.m2, for trying a build locally
//   ./gradlew publishAllPublicationsToGitHubPackagesRepository  -> GitHub Packages (CI, on release tags)
//   ./gradlew publishToMavenCentral                            -> Maven Central (CI, when the secrets exist)
//
// Coordinates come from gradle.properties (kcoap.group, kcoap.version); the release workflow sets the
// version from the tag. Signing is enabled when a key is provided (ORG_GRADLE_PROJECT_signingInMemoryKey).
// The POM names the license (kcoap.license.name, kcoap.license.url), the website, the maintainer, the issue
// tracker and the repository (KcoapProject.kt); the API reference carries the copyright footer.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

val repository = providers.gradleProperty("kcoap.repository").getOrElse("adihack/kcoap")

// Maven Central rejects a POM without a license, so a module never publishes without one.
fun requiredProperty(name: String): String =
    providers.gradleProperty(name).orNull ?: error("$name is not set: every kcoap POM names the license (gradle.properties)")

val licenseName = requiredProperty("kcoap.license.name")
val licenseUrl = requiredProperty("kcoap.license.url")

mavenPublishing {
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    publishToMavenCentral()
    // CI passes an empty value when the secret is not configured, which means "do not sign".
    if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) signAllPublications()
    // From the properties rather than project.group and project.version: coordinates() copies its
    // arguments when it is called, and when this plugin ran before the tier plugin had set the group and
    // version, every module was published as kcoap:<module>:unspecified.
    coordinates(providers.gradleProperty("kcoap.group").get(), project.name, providers.gradleProperty("kcoap.version").get())

    pom {
        name.set(project.name)
        description.set(provider { project.description ?: "kcoap: CoAP, OSCORE and EDHOC for Kotlin Multiplatform (${project.name})" })
        url.set(KcoapProject.WEBSITE)
        inceptionYear.set("2026")
        licenses {
            license {
                name.set(licenseName)
                url.set(licenseUrl)
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set(KcoapProject.MAINTAINER_ID)
                name.set(KcoapProject.MAINTAINER_NAME)
                email.set(KcoapProject.MAINTAINER_EMAIL)
                url.set(KcoapProject.MAINTAINER_URL)
                roles.add("maintainer")
            }
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/$repository/issues")
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

// Dokka reads module documentation in its own format: a "# Module <name>" header, and no other line may
// start with "#". The module README is rewritten into it, with headings as setext or bold text.
val writeModuleDoc = tasks.register<WriteDokkaModuleDoc>("writeModuleDoc") {
    moduleName.set(project.name)
    readme.from(layout.projectDirectory.file("README.md").asFile.takeIf { it.exists() } ?: emptyList<File>())
    output.set(layout.buildDirectory.file("dokka-module/module.md"))
}

dokka {
    moduleName.set(project.name)
    pluginsConfiguration.withType<DokkaHtmlPluginParameters>().configureEach {
        footerMessage.set(KcoapProject.DOKKA_FOOTER)
    }
    dokkaSourceSets.configureEach {
        includes.from(writeModuleDoc.flatMap { it.output })
        sourceLink {
            localDirectory.set(layout.projectDirectory.dir("src"))
            remoteUrl("https://github.com/$repository/tree/main/${project.name}/src")
            remoteLineSuffix.set("#L")
        }
    }
}

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.Platform
import org.gradle.api.Project
import org.gradle.api.artifacts.repositories.PasswordCredentials
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.configure

/**
 * Publishes this project as `<kcoap.group>:<project name>:<kcoap.version>` with the [platform]'s artifacts,
 * to Maven Central and GitHub Packages, signed when a key is provided, with kcoap's POM. Shared by the
 * library modules (`kcoap.publishing`, Kotlin Multiplatform) and the BOM (`kcoap.bom`, a Java platform), so
 * that every artifact kcoap publishes carries the same coordinates and the same POM.
 *
 * The POM names the license (`kcoap.license.name`, `kcoap.license.url` in gradle.properties), the website,
 * the maintainer, the issue tracker and the repository (`kcoap.repository`, by default `adihack/kcoap`;
 * [KcoapProject]). Maven Central rejects a POM without a license, so a project without the two license
 * properties fails to configure instead.
 *
 *     ./gradlew publishToMavenLocal                              -> ~/.m2, for trying a build locally
 *     ./gradlew publishAllPublicationsToGitHubPackagesRepository  -> GitHub Packages (CI, on release tags)
 *     ./gradlew publishToMavenCentral                            -> Maven Central (CI, when the secrets exist)
 */
fun Project.configureKcoapMavenPublishing(platform: Platform) {
    val repository = kcoapRepository()
    val licenseName = requiredKcoapProperty("kcoap.license.name")
    val licenseUrl = requiredKcoapProperty("kcoap.license.url")

    extensions.configure<MavenPublishBaseExtension> {
        configure(platform)
        publishToMavenCentral()
        // CI passes an empty value when the secret is not configured, which means "do not sign".
        if (!providers.gradleProperty("signingInMemoryKey").orNull.isNullOrBlank()) signAllPublications()
        // From the properties rather than project.group and project.version: coordinates() copies its
        // arguments when it is called, and when this ran before the tier plugin had set the group and
        // version, every module was published as kcoap:<module>:unspecified.
        coordinates(kcoapGroup(), this@configureKcoapMavenPublishing.name, kcoapVersion())

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

    extensions.configure<PublishingExtension> {
        repositories {
            // Credentials: ORG_GRADLE_PROJECT_GitHubPackagesUsername / ORG_GRADLE_PROJECT_GitHubPackagesPassword.
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/$repository")
                credentials(PasswordCredentials::class.java)
            }
        }
    }
}

/** The Maven group of every kcoap artifact, `kcoap.group` in gradle.properties. */
fun Project.kcoapGroup(): String = providers.gradleProperty("kcoap.group").get()

/** The version of every kcoap artifact, `kcoap.version` in gradle.properties or `-Pkcoap.version`. */
fun Project.kcoapVersion(): String = providers.gradleProperty("kcoap.version").get()

/** The GitHub repository, `owner/name`, that the POMs and the API reference link to. */
fun Project.kcoapRepository(): String = providers.gradleProperty("kcoap.repository").getOrElse("adihack/kcoap")

private fun Project.requiredKcoapProperty(name: String): String =
    providers.gradleProperty(name).orNull ?: error("$name is not set: every kcoap POM names the license (gradle.properties)")

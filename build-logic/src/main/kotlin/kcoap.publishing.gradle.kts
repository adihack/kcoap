// Publishing and API documentation for every kcoap library module, applied by the tier plugins unless
// the module applies kcoap.unpublished first. kcoap-bom (kcoap.bom) constrains every module that applies
// this plugin, and makes the module depend on it as a platform, so a new published module joins the BOM,
// and its version alignment, by itself.
//
//   ./gradlew publishToMavenLocal                              -> ~/.m2, for trying a build locally
//   ./gradlew publishAllPublicationsToGitHubPackagesRepository  -> GitHub Packages (CI, on release tags)
//   ./gradlew publishToMavenCentral                            -> Maven Central (CI, when the secrets exist)
//
// Coordinates come from gradle.properties (kcoap.group, kcoap.version); the release workflow sets the
// version from the tag. Signing is enabled when a key is provided (ORG_GRADLE_PROJECT_signingInMemoryKey).
// The POM names the license (kcoap.license.name, kcoap.license.url), the website, the maintainer, the issue
// tracker and the repository (KcoapPublishing.kt, KcoapProject.kt); the API reference carries the copyright
// footer.

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.dokka.gradle.engine.plugins.DokkaHtmlPluginParameters

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

configureKcoapMavenPublishing(KotlinMultiplatform(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))

val repository = kcoapRepository()

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

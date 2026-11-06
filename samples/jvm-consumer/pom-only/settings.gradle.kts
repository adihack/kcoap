// The JVM consumer as a Maven build sees kcoap: the same program, with every dependency resolved from its POM
// alone, never from Gradle module metadata, the BOM imported from its POM and the JVM artifacts (kcoap-coap-jvm)
// named, as getting started tells Maven builds to. samples/jvm-consumer checks the module metadata Gradle builds
// read; this build checks the POMs. ../README.md says what it catches.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// mavenLocal() follows -Dmaven.repo.local, which the configuration cache does not see on its own: reading it here
// makes it an input, so a run against another local repository resolves kcoap again instead of reusing the result
// of the last one.
val localMavenRepository: String? = providers.systemProperty("maven.repo.local").orNull

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // kcoap, as publishToMavenLocal left it, and from nowhere else; its POMs only, as Maven reads them. A
        // POM that is missing fails resolution, where Maven would only warn.
        exclusiveContent {
            forRepository {
                mavenLocal {
                    metadataSources {
                        mavenPom()
                        ignoreGradleMetadataRedirection()
                    }
                }
            }
            filter { includeGroup("dev.kcoap") }
        }
        // Everything else from its POM too, so that a kcoap POM naming a library's multiplatform root artifact
        // (kotlinx-coroutines-core instead of kotlinx-coroutines-core-jvm) is not rescued by its module metadata.
        mavenCentral {
            metadataSources {
                mavenPom()
                ignoreGradleMetadataRedirection()
            }
        }
    }
    versionCatalogs {
        // kcoap's own catalog, for the Kotlin version kcoap is built with, plus the Kotlin JVM plugin.
        create("libs") {
            from(files("../../../gradle/libs.versions.toml"))
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
        }
    }
}

rootProject.name = "jvm-consumer-pom-only"

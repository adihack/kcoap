// A JVM application that uses kcoap as any other would: from a Maven repository, here the local one that
// `./gradlew publishToMavenLocal` in the repository root fills, with every kcoap version set by kcoap-bom. It is a
// build of its own, not part of kcoap's, so that it sees only what kcoap publishes. README.md says how to build it
// and what it checks.

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
        // kcoap, as publishToMavenLocal left it, and from nowhere else: a kcoap module the commit did not publish
        // fails resolution instead of coming from Maven Central. Nothing else comes from the local repository.
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("dev.kcoap") }
        }
        mavenCentral()
    }
    versionCatalogs {
        // kcoap's own catalog, for the Kotlin version kcoap is built with, plus the Kotlin JVM plugin, which only
        // the samples use.
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
            plugin("kotlin-jvm", "org.jetbrains.kotlin.jvm").versionRef("kotlin")
        }
    }
}

rootProject.name = "jvm-consumer"

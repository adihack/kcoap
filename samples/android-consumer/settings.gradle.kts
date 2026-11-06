// An Android app that uses kcoap as any other app would: from a Maven repository, here the local one that
// `./gradlew publishToMavenLocal` in the repository root fills. It is a build of its own, not part of
// kcoap's, because it needs the Android SDK. README.md says how to build it and what it checks.

pluginManagement {
    repositories {
        google()
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
        google()
        mavenCentral()
    }
    versionCatalogs {
        // kcoap's own catalog, for the Kotlin version kcoap is built with and its Android minSdk, plus the
        // Android Gradle plugin and kotlin.test on JUnit 4 for the unit tests, which only this sample uses.
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
            version("agp", "9.4.1")
            plugin("android-application", "com.android.application").versionRef("agp")
            plugin("kotlin-android", "org.jetbrains.kotlin.android").versionRef("kotlin")
            library("kotlin-test-junit", "org.jetbrains.kotlin", "kotlin-test-junit").versionRef("kotlin")
        }
    }
}

rootProject.name = "android-consumer"

include(":app")

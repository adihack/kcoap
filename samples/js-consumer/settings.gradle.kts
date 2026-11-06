// A browser application that uses kcoap as any other would: Kotlin/JS compiled to a production webpack bundle,
// with kcoap from a Maven repository, here the local one that `./gradlew publishToMavenLocal` in the repository
// root fills, and every kcoap version set by kcoap-bom. It is a build of its own, not part of kcoap's, so that it
// sees only what kcoap publishes. README.md says how to build it, what it checks and what the bundle weighs.

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        // kcoap, as publishToMavenLocal left it; nothing else comes from the local repository.
        mavenLocal {
            content { includeGroup("dev.kcoap") }
        }
        mavenCentral()
        // Node.js and Yarn, which the Kotlin Gradle plugin downloads to bundle the browser code: the repositories
        // kcoap's own build declares, so both builds share Gradle's cached copies. The plugin would add them to
        // the project itself, which this build forbids, so build.gradle.kts tells it not to.
        ivy("https://nodejs.org/dist") {
            name = "Node.js distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
    }
    versionCatalogs {
        // kcoap's own catalog, for the Kotlin version kcoap is built with and ktor's, plus the Kotlin
        // Multiplatform plugin, which only the samples apply directly.
        create("libs") {
            from(files("../../gradle/libs.versions.toml"))
            plugin("kotlin-multiplatform", "org.jetbrains.kotlin.multiplatform").versionRef("kotlin")
        }
    }
}

rootProject.name = "js-consumer"

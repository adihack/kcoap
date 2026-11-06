// Marks a core module: protocol, wire or SPI code, which is common Kotlin only, with no expect/actual and
// no platform imports, so that it builds for every target of the UNIVERSAL tier
// (docs/target-architecture.md §1, principle 1).
//
// `check` runs verifyCommonOnly, which fails when a main source set other than commonMain has files.
// Platform test source sets stay allowed (jvmTest holds the differential and interop tests). Apply it
// next to the tier plugin in each core module; adapters do not, because a UNIVERSAL adapter may need
// appleMain or webMain.
//
//   plugins {
//       id("kcoap.kmp-library")
//       id("kcoap.core")
//   }

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

val multiplatform = extensions.getByType<KotlinMultiplatformExtension>()

val verifyCommonOnly = tasks.register<VerifyCommonOnly>("verifyCommonOnly") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Fails when this core module has main code outside commonMain."
    moduleName.set(project.name)
    projectDirectory.set(layout.projectDirectory)
    // Every src/<name>Main directory, also those of targets the module does not declare.
    platformMainSources.from(
        layout.projectDirectory.dir("src").asFileTree.matching {
            include("*Main/**")
            exclude("commonMain/**")
        },
    )
    // And the directories of the declared main source sets, wherever they point.
    platformMainSources.from(
        provider {
            multiplatform.sourceSets
                .filter { it.name.endsWith("Main") && it.name != "commonMain" }
                .flatMap { listOf(it.kotlin.sourceDirectories, it.resources.sourceDirectories) }
        },
    )
}

tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
    dependsOn(verifyCommonOnly)
}

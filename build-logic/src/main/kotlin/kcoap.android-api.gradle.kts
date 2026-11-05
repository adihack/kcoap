// Android apps use kcoap's jvm artifacts, so a module's jvm jar may use only the Java APIs of kcoap's
// minSdk (android-minSdk in the version catalog). The tier plugins apply this plugin, which gives every
// multiplatform module two tasks (CheckAndroidApi.kt):
//
//   checkAndroidApi              animal-sniffer over the module's jvm jar against the API signature of that
//                                level; fails on any use the level lacks. Part of check and of
//                                `scripts/test unit`.
//   androidApiDependencyReport   the same over the module's external jvm runtime dependencies (ktor,
//                                cryptography-kotlin, Bouncy Castle, ...); writes
//                                build/reports/android-api/dependencies.txt and never fails
//
// Neither needs the Android SDK. samples/android-consumer checks the rest with the Android toolchain in
// CI: D8, R8 and lint.

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.bundling.Jar

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val minSdk = libs.findVersion("android-minSdk").get().requiredVersion.toInt()

val animalSniffer = configurations.detachedConfiguration(
    dependencies.create(libs.findLibrary("animal-sniffer").get().get()),
)
val apiSignature = libs.findLibrary("android-api-signature").get().get().let { signature ->
    check(signature.name == "android-api-level-$minSdk") {
        "The version catalog's android-api-signature (${signature.name}) must be the one of android-minSdk ($minSdk)"
    }
    configurations.detachedConfiguration(dependencies.create("${signature.module}:${signature.version}@signature"))
}

fun CheckAndroidApi.useAnimalSniffer() {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    apiLevel.set(minSdk)
    signature.from(apiSignature)
    toolClasspath.from(animalSniffer)
}

pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
    val runtimeClasspath = configurations.named("jvmRuntimeClasspath")

    val checkAndroidApi = tasks.register<CheckAndroidApi>("checkAndroidApi") {
        description = "Fails when the jvm jar uses a Java API that Android API level $minSdk does not have."
        useAnimalSniffer()
        classes.from(tasks.named<Jar>("jvmJar").flatMap { it.archiveFile })
        classpath.from(runtimeClasspath)
        failOnViolation.set(true)
        report.set(layout.buildDirectory.file("reports/android-api/jvm.txt"))
    }

    tasks.register<CheckAndroidApi>("androidApiDependencyReport") {
        description = "Lists the uses of Java APIs that Android API level $minSdk does not have in the " +
            "module's external jvm runtime dependencies."
        useAnimalSniffer()
        // The kcoap modules on the classpath are checked by their own checkAndroidApi.
        classes.from(
            runtimeClasspath.map { configuration ->
                configuration.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }.files
            },
        )
        classpath.from(runtimeClasspath)
        failOnViolation.set(false)
        report.set(layout.buildDirectory.file("reports/android-api/dependencies.txt"))
    }

    tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME) {
        dependsOn(checkAndroidApi)
    }
}

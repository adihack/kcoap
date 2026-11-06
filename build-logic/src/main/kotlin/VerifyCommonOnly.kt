import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Fails when a core module (`kcoap.core`) has main code outside commonMain. Core modules build for every
 * target from common code only (docs/target-architecture.md §1); runtime-specific code belongs in an
 * adapter module.
 */
abstract class VerifyCommonOnly : DefaultTask() {
    /** The files of every main source set other than commonMain. There must be none. */
    @get:InputFiles
    @get:IgnoreEmptyDirectories
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val platformMainSources: ConfigurableFileCollection

    @get:Input
    abstract val moduleName: Property<String>

    /** The paths in the failure message are relative to it. */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val base = projectDirectory.get().asFile
        val found = platformMainSources.asFileTree.files.map { it.relativeTo(base).invariantSeparatorsPath }.sorted()
        if (found.isEmpty()) return
        throw GradleException(
            buildString {
                append("${moduleName.get()} is a core module (kcoap.core), so its main code must be in commonMain ")
                append("and build for every target (docs/target-architecture.md §1). ")
                append("Move these files to commonMain, or to an adapter module:")
                found.forEach { append("\n  ").append(it) }
            },
        )
    }
}

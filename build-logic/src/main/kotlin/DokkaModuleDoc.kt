import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Rewrites a module README into the body of a Dokka module documentation file.
 *
 * Dokka splits that file at every line starting with `#`, so the README's own title is dropped (Dokka
 * prints the module name), second-level headings become setext headings, deeper ones bold text, and
 * lines inside code blocks that start with `#` are indented by one space.
 */
fun dokkaModuleBody(readme: List<String>): String {
    var inCode = false
    val lines = readme.dropWhile { it.isBlank() }.let { if (it.firstOrNull()?.startsWith("# ") == true) it.drop(1) else it }
    return lines.flatMap { line ->
        when {
            line.trimStart().startsWith("```") -> {
                inCode = !inCode
                listOf(line)
            }
            !line.startsWith("#") -> listOf(line)
            inCode -> listOf(" $line")
            line.startsWith("## ") -> line.removePrefix("## ").trim().let { listOf(it, "-".repeat(it.length)) }
            else -> listOf("**${line.trimStart('#').trim()}**")
        }
    }.joinToString("\n").trim()
}

/** Writes a module's README as its Dokka module documentation (see [dokkaModuleBody]). */
@CacheableTask
abstract class WriteDokkaModuleDoc : DefaultTask() {
    @get:Input
    abstract val moduleName: Property<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val readme: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun write() {
        val source = readme.files.firstOrNull { it.exists() }
        val body = source?.let { dokkaModuleBody(it.readLines()) } ?: ""
        output.get().asFile.apply { parentFile.mkdirs() }.writeText("# Module ${moduleName.get()}\n\n$body\n")
    }
}

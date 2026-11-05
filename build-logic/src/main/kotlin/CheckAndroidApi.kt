import org.codehaus.mojo.animal_sniffer.ClassListBuilder
import org.codehaus.mojo.animal_sniffer.SignatureChecker
import org.codehaus.mojo.animal_sniffer.logging.Logger
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import javax.inject.Inject

/**
 * Checks jars against an Android API level with animal-sniffer: every Java class, method and field they
 * use must exist in that level's API signature (`net.sf.androidscents.signature`), unless it is in the
 * checked jars themselves or on the [classpath], which the app ships with them. Using something the
 * device lacks fails there at runtime with `NoClassDefFoundError` or `NoSuchMethodError`; this finds it
 * on any JVM, without the Android SDK.
 *
 * What animal-sniffer lets pass: the bootstrap methods of `invokedynamic` (lambdas and string
 * concatenation), which D8 rewrites into plain code for every API level, and classes or methods annotated
 * `@IgnoreJRERequirement`, which is how a library such as kotlinx-coroutines marks a use it guards at
 * runtime. A `try` around a use does not count: the check cannot know what the `catch` does.
 *
 * The violations go to [report], one per line, and fail the task when [failOnViolation] is set.
 */
@CacheableTask
abstract class CheckAndroidApi : DefaultTask() {
    /** The jars to check. */
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    /** What ships with [classes], their runtime classpath: uses of it are not checked. */
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    /** The animal-sniffer signature of the Android API level. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val signature: ConfigurableFileCollection

    /** The API level of [signature], for the messages. */
    @get:Input
    abstract val apiLevel: Property<Int>

    @get:Input
    abstract val failOnViolation: Property<Boolean>

    /** animal-sniffer and its dependencies, loaded in a class loader of their own. */
    @get:Classpath
    abstract val toolClasspath: ConfigurableFileCollection

    @get:OutputFile
    abstract val report: RegularFileProperty

    @get:Inject
    abstract val workers: WorkerExecutor

    @TaskAction
    fun check() {
        workers.classLoaderIsolation { classpath.from(toolClasspath) }.submit(AndroidApiCheck::class.java) {
            classes.from(this@CheckAndroidApi.classes)
            classpath.from(this@CheckAndroidApi.classpath)
            signature.set(this@CheckAndroidApi.signature.singleFile)
            report.set(this@CheckAndroidApi.report)
        }
        workers.await()

        val violations = report.get().asFile.readLines().filter { it.isNotBlank() }
        if (violations.isEmpty()) return
        val message = buildString {
            append("${violations.size} uses of Java APIs that Android API level ${apiLevel.get()} does not have; ")
            append("on such a device they fail with NoClassDefFoundError or NoSuchMethodError:")
            violations.forEach { append("\n  ").append(it) }
            append("\nReport: ${report.get().asFile}")
        }
        if (failOnViolation.get()) throw GradleException(message) else logger.warn(message)
    }
}

/** Runs animal-sniffer for [CheckAndroidApi], in the class loader that holds animal-sniffer. */
abstract class AndroidApiCheck : WorkAction<AndroidApiCheck.Parameters> {
    interface Parameters : WorkParameters {
        val classes: ConfigurableFileCollection
        val classpath: ConfigurableFileCollection
        val signature: RegularFileProperty
        val report: RegularFileProperty
    }

    override fun execute() {
        val violations = Violations()
        val checked = parameters.classes.files.filter { it.exists() }.sortedBy { it.name }

        // The app ships the checked jars and their classpath, so whatever they define is there on every API
        // level, and animal-sniffer skips uses of it.
        val shipped = ClassListBuilder(violations).apply {
            (checked + parameters.classpath.files.filter { it.exists() }).forEach { process(it) }
        }.packages
        val checker = parameters.signature.get().asFile.inputStream().use { signature ->
            SignatureChecker(signature, shipped, violations)
        }
        // Without sources, a violation names the class file and line.
        checker.setSourcePath(emptyList())
        checked.forEach { jar ->
            violations.jar = jar.name
            checker.process(jar)
        }
        parameters.report.get().asFile.writeText(violations.lines.joinToString("\n", postfix = "\n"))
    }

    /** Collects animal-sniffer's errors as `<jar>: <class file>:<line>: Undefined reference: <use>`. */
    private class Violations : Logger {
        val lines = mutableListOf<String>()
        var jar = ""

        // animal-sniffer starts each error with the jar's absolute path, which the jar's name replaces.
        override fun error(message: String) {
            lines += "$jar: ${message.substringAfter(".jar:")}"
        }

        override fun error(message: String, t: Throwable?) = error("$message ($t)")

        // Warnings are classes animal-sniffer could not read; they must not pass unseen.
        override fun warn(message: String) = error(message)
        override fun warn(message: String, t: Throwable?) = error(message, t)
        override fun info(message: String) = Unit
        override fun info(message: String, t: Throwable?) = Unit
        override fun debug(message: String) = Unit
        override fun debug(message: String, t: Throwable?) = Unit
    }
}

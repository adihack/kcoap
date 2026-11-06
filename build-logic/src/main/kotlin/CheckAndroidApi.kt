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
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
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
 * The violations go to [report], one per line, and fail the task when [failOnViolation] is set. With a
 * [baseline], only the violations it does not list fail it: those of libraries, which were triaged once and
 * must not grow unseen when a library is updated.
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

    /**
     * The violations that were triaged and accepted, one [baselineEntry] per line; blank lines and lines
     * starting with `#` are comments. Without it every violation counts.
     */
    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

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
        val accepted = baseline.orNull?.asFile?.readLines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()
        val unseen = violations.filter { baselineEntry(it) !in accepted }
        if (unseen.isEmpty()) {
            if (violations.isNotEmpty()) logger.info("${violations.size} violations, all in ${baseline.get().asFile}")
            return
        }
        val message = buildString {
            append(if (unseen.size == 1) "1 use of a Java API" else "${unseen.size} uses of Java APIs")
            append(" that Android API level ${apiLevel.get()} does not have")
            if (baseline.isPresent) append(" and ${baseline.get().asFile.name} does not list")
            append(if (unseen.size == 1) "; on such a device it fails" else "; on such a device they fail")
            append(" with NoClassDefFoundError or NoSuchMethodError:")
            unseen.forEach { append("\n  ").append(it) }
            append("\nReport: ${report.get().asFile}")
            if (baseline.isPresent) {
                append("\nTriage each one in samples/android-consumer/README.md (\"API level findings\"): kept by R8 ")
                append("and reachable from kcoap? If it is safe, add these lines to ${baseline.get().asFile}:")
                unseen.map(::baselineEntry).distinct().forEach { append("\n  ").append(it) }
            }
        }
        if (failOnViolation.get()) throw GradleException(message) else logger.warn(message)
    }
}

/**
 * The [baseline][CheckAndroidApi.baseline] line of [violation], a line of [CheckAndroidApi.report]: the
 * jar's name without its version, the class file and the use, without the line number. A baseline line
 * then stays valid when the library is updated or its code moves, and only a new use, or a use in another
 * class, fails the check.
 *
 * `ktor-utils-jvm-3.6.1.jar: io/ktor/util/debug/IntellijIdeaDebugDetector.class:12: Undefined reference: X`
 * becomes `ktor-utils-jvm: io/ktor/util/debug/IntellijIdeaDebugDetector.class: Undefined reference: X`.
 */
fun baselineEntry(violation: String): String {
    val jar = violation.substringBefore(": ")
    val use = violation.substringAfter(": ").replace(LINE_NUMBER, "$1: ")
    return "${jar.removeSuffix(".jar").replace(VERSION, "")}: $use"
}

/** The version a Maven jar's file name ends with, from the first `-` followed by a digit. */
private val VERSION = Regex("-\\d.*$")

/** The line number animal-sniffer puts after the class file. */
private val LINE_NUMBER = Regex("^(\\S+\\.class):\\d+: ")

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

package dev.kcoap.crypto

import java.net.JarURLConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The jvm jar carries R8 and ProGuard rules for apps (`META-INF/proguard/kcoap-crypto-default.pro`), which
 * the Android Gradle plugin applies on its own. cryptography-kotlin's JDK provider refers to Bouncy Castle
 * and uses it only when it is there at runtime, so R8 fails the release build of an app without Bouncy
 * Castle on those classes unless a `-dontwarn` covers each (samples/android-consumer, classical flavor).
 * This scans the provider's jar, so a cryptography-kotlin update that refers to more of Bouncy Castle
 * fails here rather than in an app.
 */
class ConsumerRulesTest {
    @Test
    fun everyBouncyCastleClassTheJdkProviderRefersToIsCoveredByADontwarnRule() {
        val dontwarn = rules().lines().map { it.trim() }.filter { it.startsWith("-dontwarn ") }
            .map { it.removePrefix("-dontwarn ").trim() }

        val referenced = bouncyCastleReferences(JDK_PROVIDER_CLASS)

        assertTrue(referenced.isNotEmpty(), "no Bouncy Castle reference found; is $JDK_PROVIDER_CLASS still there?")
        assertEquals(emptyList(), referenced.filter { name -> dontwarn.none { covers(it, name) } })
    }

    @Test
    fun theRulesOnlySilenceMissingBouncyCastleClasses() {
        // A -keep would make every app keep code it does not use; any other rule needs its own reason and test.
        val directives = rules().lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        assertTrue(directives.isNotEmpty())
        assertTrue(directives.all { it.startsWith("-dontwarn org.bouncycastle.") }, "$directives")
    }

    private fun rules(): String {
        val url = assertNotNull(javaClass.classLoader.getResource(RULES), "$RULES is not on the classpath")
        return url.readText()
    }

    /** The Bouncy Castle classes named anywhere in the jar that holds [className], as Java class names. */
    private fun bouncyCastleReferences(className: String): List<String> {
        val url = assertNotNull(javaClass.classLoader.getResource(className.replace('.', '/') + ".class"))
        val jar = (url.openConnection() as JarURLConnection).jarFile
        return jar.use {
            it.entries().asSequence().filter { entry -> entry.name.endsWith(".class") }.flatMap { entry ->
                // Class names in the constant pool are plain ASCII, also inside descriptors (Lorg/bouncycastle/...;).
                val text = it.getInputStream(entry).use { input -> input.readBytes() }.toString(Charsets.ISO_8859_1)
                BOUNCY_CASTLE_NAME.findAll(text).map { match -> match.value.replace('/', '.') }
            }.toSortedSet().toList()
        }
    }

    /** Whether the ProGuard class filter [pattern] matches [className]; kcoap's rules use exact names and `pkg.**`. */
    private fun covers(pattern: String, className: String): Boolean =
        if (pattern.endsWith(".**")) className.startsWith(pattern.removeSuffix("**")) else pattern == className

    private companion object {
        const val RULES = "META-INF/proguard/kcoap-crypto-default.pro"
        const val JDK_PROVIDER_CLASS = "dev.whyoleg.cryptography.providers.jdk.internal.BouncyCastleBridge"
        val BOUNCY_CASTLE_NAME = Regex("""org/bouncycastle/[A-Za-z0-9_/$]+""")
    }
}

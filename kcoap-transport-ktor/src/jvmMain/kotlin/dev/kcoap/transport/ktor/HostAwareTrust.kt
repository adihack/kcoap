package dev.kcoap.transport.ktor

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * A platform trust manager's check that takes the server's host, where the platform has one: Android's
 * `android.net.http.X509TrustManagerExtensions` (API level 17). From Android 7 the platform trust manager applies
 * the app's network security configuration, and a `<domain-config>` there, with its own trust anchors or
 * `<pin-set>`, applies to the host the check names; the two-argument `checkServerTrusted` refuses every chain
 * once the configuration has one. On the JVM the class does not exist, [find] returns null, and the two-argument
 * check runs. The class is looked up by name, so the JVM jar needs no Android API.
 */
internal class HostAwareTrust private constructor(private val extensions: Any, private val check: Method) {
    /**
     * Validates [chain] for [host] as the platform's own TLS would.
     *
     * @throws CertificateException when the platform refuses the chain.
     */
    fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?, host: String) {
        try {
            check.invoke(extensions, chain, authType, host)
        } catch (e: InvocationTargetException) {
            when (val cause = e.targetException) {
                is CertificateException -> throw cause
                is Exception -> throw CertificateException("The platform trust manager failed for $host", cause)
                else -> throw cause
            }
        }
    }

    companion object {
        /** Android's public wrapper for the host-aware check of its platform trust managers. */
        const val ANDROID_EXTENSIONS: String = "android.net.http.X509TrustManagerExtensions"

        /**
         * The host-aware check of [trust], through [className]'s `checkServerTrusted(X509Certificate[], String,
         * String)`; null where that class does not exist or does not take [trust], which Android's wrapper refuses
         * when the trust manager has no host-aware check.
         */
        fun find(trust: X509TrustManager, className: String = ANDROID_EXTENSIONS): HostAwareTrust? {
            val type = try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                return null
            }
            return try {
                val extensions = type.getConstructor(X509TrustManager::class.java).newInstance(trust)
                val check = type.getMethod(
                    "checkServerTrusted",
                    Array<X509Certificate>::class.java,
                    String::class.java,
                    String::class.java,
                )
                HostAwareTrust(extensions, check)
            } catch (e: ReflectiveOperationException) {
                null
            }
        }
    }
}

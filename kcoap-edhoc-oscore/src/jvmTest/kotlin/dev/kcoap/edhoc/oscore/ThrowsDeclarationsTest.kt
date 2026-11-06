package dev.kcoap.edhoc.oscore

import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.security.ClientMessageSecurity
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.ProtectedRequest
import dev.kcoap.coap.security.ServerMessageSecurity
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.common.KcoapException
import dev.kcoap.edhoc.EdhocInitiator
import dev.kcoap.edhoc.EdhocResponder
import dev.kcoap.oscore.SecurityContext
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `@Throws` lists of WP-D2, as docs/architecture.md ("Swift") and CHANGELOG.md give them, read back from the
 * class files. Kotlin/Native turns an exception into an `NSError` only for a function that lists its type, and ends
 * a Swift process for any other, so a list that loses an entry, or a new overload without one, is a crash on Apple
 * platforms. The common `@Throws` is `kotlin.jvm.Throws` on the JVM, which writes the same list into the method's
 * `throws` clause, so this test on the JVM sees what the Apple targets get. This module sees every module involved.
 */
class ThrowsDeclarationsTest {
    /** The list of the public suspend entry points: the root of the runtime failures, and the caller's cancellation. */
    private val runtimeFailures: Set<Class<*>> = setOf(KcoapException::class.java, CancellationException::class.java)

    /** The list of the message-security SPI: its signal between the layers, and the caller's cancellation. */
    private val spiFailures: Set<Class<*>> = setOf(MessageSecurityException::class.java, CancellationException::class.java)

    /**
     * The public methods of this class named [names]. A parameter of a value class, such as `CoapCode`, mangles the
     * JVM name with a hash (`request-vNWM5LQ`), so the name before the hash counts. The `name$default` bridges of
     * default arguments are left out, as Swift does not see them. Every name must have at least one method.
     */
    private fun Class<*>.methodsNamed(vararg names: String): List<Method> {
        val found = methods.filter {
            '$' !in it.name && it.kotlinName in names && Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge
        }
        assertEquals(names.toSet(), found.map { it.kotlinName }.toSet(), "$simpleName has a public method of each name")
        return found
    }

    private val Method.kotlinName: String get() = name.substringBefore('-')

    private fun assertDeclares(expected: Set<Class<*>>, methods: List<Method>) {
        for (method in methods) {
            val signature = "${method.declaringClass.simpleName}.${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
            assertEquals(expected, method.exceptionTypes.toSet(), "@Throws of $signature")
        }
    }

    @Test
    fun everyRequestPingAndDiscoverOfCoapClientListsKcoapExceptionAndCancellation() {
        val methods = CoapClient::class.java.methodsNamed(
            "request", "get", "delete", "post", "put", "fetch", "patch", "ipatch", "ping", "discover",
        )
        // Each of the seven method shortcuts has a CoapUri and a String overload, besides request, ping and discover.
        assertTrue(methods.size >= 20, "${methods.size} overloads")
        assertDeclares(runtimeFailures, methods)
    }

    @Test
    fun bothPingsOfCoapEndpointListKcoapExceptionAndCancellation() {
        val methods = CoapEndpoint::class.java.methodsNamed("ping")
        assertEquals(2, methods.size, "ping(peer) and ping(peer, custody)")
        assertDeclares(runtimeFailures, methods)
    }

    @Test
    fun theEightEdhocStepsListKcoapExceptionAndCancellation() {
        assertDeclares(
            runtimeFailures,
            EdhocInitiator::class.java.methodsNamed("createMessage1", "processMessage2", "createMessage3", "processMessage4"),
        )
        assertDeclares(
            runtimeFailures,
            EdhocResponder::class.java.methodsNamed("processMessage1", "createMessage2", "processMessage3", "createMessage4"),
        )
    }

    @Test
    fun theEdhocOverCoapEntryPointsListKcoapExceptionAndCancellation() {
        assertDeclares(runtimeFailures, EdhocClientHandshake::class.java.methodsNamed("run", "runCombined"))
        assertDeclares(runtimeFailures, EdhocServer::class.java.methodsNamed("completeCombined"))
        val installByUri = PeerSecurity::class.java.methodsNamed("install").filter { it.parameterTypes.first() == CoapClient::class.java }
        assertEquals(1, installByUri.size, "install(client, server, security)")
        assertDeclares(runtimeFailures, installByUri)
    }

    @Test
    fun deriveAndOscoreContextListKcoapExceptionAndCancellation() {
        assertDeclares(runtimeFailures, SecurityContext.Companion::class.java.methodsNamed("derive"))
        assertDeclares(runtimeFailures, Class.forName("dev.kcoap.edhoc.oscore.OscoreFromEdhocKt").methodsNamed("oscoreContext"))
    }

    /** URIs are often typed in, so Swift catches a malformed one; it is the only caller mistake that is listed. */
    @Test
    fun coapUriParseListsIllegalArgumentException() {
        assertDeclares(setOf(IllegalArgumentException::class.java), CoapUri.Companion::class.java.methodsNamed("parse"))
    }

    /** Kotlin/Native requires every override, OSCORE's included, to keep the list of the member it overrides. */
    @Test
    fun theFourMessageSecurityOperationsListMessageSecurityExceptionAndCancellation() {
        assertDeclares(spiFailures, ClientMessageSecurity::class.java.methodsNamed("protectRequest"))
        assertDeclares(spiFailures, ProtectedRequest::class.java.methodsNamed("unprotectResponse"))
        assertDeclares(spiFailures, ServerMessageSecurity::class.java.methodsNamed("unprotectRequest"))
        assertDeclares(spiFailures, UnprotectedRequest::class.java.methodsNamed("protectResponse"))
    }
}

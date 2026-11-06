package dev.kcoap.swiftsmoke

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapRequest
import dev.kcoap.coap.CoapUri
import dev.kcoap.coap.client.CoapClient
import dev.kcoap.coap.client.CoapClientConfig
import dev.kcoap.testkit.InMemoryNetwork
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * What the Swift smoke test (`src/swift/SwiftSmoke.swift`) needs beyond kcoap's own API, because Swift cannot
 * write it: a `CoroutineScope` and a transport for a client, a `kotlin.time.Duration` and a `CoapCode`, which are
 * value classes and reach Objective-C without a type of their own, and Kotlin's view of an exception's class.
 * Everything the test checks it calls on kcoap directly: the configuration builder, `CoapUri.parse` and
 * `CoapClient.request`.
 */
public object SmokeFixture {
    /** Sets [CoapClientConfig.Builder.requestTimeout] to [milliseconds]. */
    public fun applyRequestTimeout(builder: CoapClientConfig.Builder, milliseconds: Long) {
        builder.requestTimeout = milliseconds.milliseconds
    }

    /** [CoapClientConfig.requestTimeout] in whole milliseconds, or -1 when it is not set. */
    public fun requestTimeoutMilliseconds(config: CoapClientConfig): Long = config.requestTimeout?.inWholeMilliseconds ?: -1

    /** A Confirmable GET of [uri]. */
    public fun getRequest(uri: CoapUri): CoapRequest = CoapRequest.Builder(CoapCode.GET, uri).build()

    /**
     * A client with [config] on a new in-memory network (kcoap-testkit's [InMemoryNetwork]) where nothing but the
     * client is attached, so every request it sends goes unanswered and fails after
     * [CoapClientConfig.requestTimeout]. It runs on [Dispatchers.Default] in a scope of its own; `close()` closes
     * its endpoint.
     */
    public suspend fun unansweredClient(config: CoapClientConfig): CoapClient {
        val transport = InMemoryNetwork().attach()
        return CoapClient(transport, CoroutineScope(SupervisorJob() + Dispatchers.Default), config)
    }

    /**
     * Whether [throwable], the Kotlin exception of an `NSError`'s `userInfo`, is an [IllegalArgumentException]. Swift
     * could test that itself only if the framework exported `KotlinIllegalArgumentException`, which it does only
     * when a signature of the exported API names the class.
     */
    public fun isIllegalArgument(throwable: Any?): Boolean = throwable is IllegalArgumentException

    /** The Kotlin class of [value], for the test's messages, or `null`. */
    public fun kotlinClassName(value: Any?): String =
        if (value == null) "null" else value::class.qualifiedName ?: value::class.simpleName ?: "an anonymous class"
}

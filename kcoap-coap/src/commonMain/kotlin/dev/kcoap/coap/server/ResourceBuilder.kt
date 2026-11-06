package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapResponse

/** Marks the resource DSL, so nested blocks do not see the outer builder's members. */
@DslMarker
public annotation class CoapServerDsl

/** A request handler in the DSL: the [RequestContext] is the receiver. */
public typealias RequestHandlerFunction = suspend RequestContext.() -> CoapResponse

/**
 * Defines resources inline, as an alternative to subclassing [CoapResource]:
 *
 * ```
 * val server = CoapServer(endpoint) {
 *     resource("sensors") {
 *         resource("temp") {
 *             attributes = ResourceAttributes(resourceTypes = listOf("temperature-c"))
 *             observable()
 *             get { CoapResponse.content(readTemperature()) }
 *         }
 *     }
 *     resource("echo") { post { CoapResponse(CoapCode.CHANGED, payload = payload) } }
 *     resource("api") {
 *         access = AccessPolicy.Authenticated // for /api and everything below it
 *         resource("config") { put { CoapResponse.changed() } }
 *     }
 *     add(FirmwareResource())
 * }
 * ```
 *
 * A resource without handlers only groups children: it answers 4.04 and is not listed in
 * `/.well-known/core`. One with handlers answers 4.05 for the other methods.
 */
@CoapServerDsl
public class ResourceBuilder internal constructor(private val resource: DslResource) {
    /** Link attributes for `/.well-known/core`. */
    public var attributes: ResourceAttributes
        get() = resource.dslAttributes
        set(value) {
            resource.dslAttributes = value
        }

    /** Whether `/.well-known/core` lists the resource (when it has handlers). */
    public var discoverable: Boolean
        get() = resource.dslDiscoverable
        set(value) {
            resource.dslDiscoverable = value
        }

    /**
     * Who may use the resource and the resources below it without a policy of their own (ADR 0008); null, the
     * default, inherits the parent's. At the top level of the server's routes it is the root's, which governs
     * every resource that sets none. See [AccessPolicy].
     */
    public var access: AccessPolicy?
        get() = resource.dslAccess
        set(value) {
            resource.dslAccess = value
        }

    /** Makes the resource observable (RFC 7641) with notifications of [type]. */
    public fun observable(type: CoapMessageType = CoapMessageType.CON) {
        require(type == CoapMessageType.CON || type == CoapMessageType.NON) { "Notifications are CON or NON" }
        resource.dslObservable = true
        resource.dslNotificationType = type
    }

    /** Critical options the handlers process themselves (see [CoapResource.recognizedOptions]). */
    public fun recognize(vararg optionNumbers: Int) {
        resource.dslRecognized = resource.dslRecognized + optionNumbers.toSet()
    }

    /** Handles GET (RFC 7252 §5.8.1); for an observable resource also every notification. */
    public fun get(handler: RequestHandlerFunction): Unit = on(CoapCode.GET, handler)
    /** Handles POST (RFC 7252 §5.8.2). */
    public fun post(handler: RequestHandlerFunction): Unit = on(CoapCode.POST, handler)
    /** Handles PUT (RFC 7252 §5.8.3). */
    public fun put(handler: RequestHandlerFunction): Unit = on(CoapCode.PUT, handler)
    /** Handles DELETE (RFC 7252 §5.8.4). */
    public fun delete(handler: RequestHandlerFunction): Unit = on(CoapCode.DELETE, handler)
    /** Handles FETCH (RFC 8132 §2). */
    public fun fetch(handler: RequestHandlerFunction): Unit = on(CoapCode.FETCH, handler)
    /** Handles PATCH (RFC 8132 §3). */
    public fun patch(handler: RequestHandlerFunction): Unit = on(CoapCode.PATCH, handler)
    /** Handles iPATCH (RFC 8132 §3). */
    public fun ipatch(handler: RequestHandlerFunction): Unit = on(CoapCode.IPATCH, handler)

    /** Handles [method], for methods without a shortcut. */
    public fun on(method: CoapCode, handler: RequestHandlerFunction) {
        require(method.isRequest) { "$method is not a method" }
        resource.handlers[method] = handler
    }

    /** Adds a child defined by [block] and returns it, for example to call [ObservableResource.notifyObservers]. */
    public fun resource(name: String, block: ResourceBuilder.() -> Unit = {}): ObservableResource {
        val child = DslResource(name)
        ResourceBuilder(child).block()
        resource.add(child)
        return child
    }

    /** Adds a subclassed resource as a child. */
    public fun add(child: CoapResource): CoapResource = resource.add(child)
}

/** What the DSL builds. Observable only when asked to be. */
internal class DslResource(name: String) : ObservableResource(name) {
    val handlers = HashMap<CoapCode, RequestHandlerFunction>()
    var dslAttributes: ResourceAttributes = ResourceAttributes.NONE
    var dslDiscoverable = true
    var dslObservable = false
    var dslNotificationType = CoapMessageType.CON
    var dslRecognized: Set<Int> = emptySet()
    var dslAccess: AccessPolicy? = null

    override val attributes: ResourceAttributes get() = dslAttributes
    override val access: AccessPolicy? get() = dslAccess
    override val isDiscoverable: Boolean get() = dslDiscoverable && handlers.isNotEmpty()
    override val isObservable: Boolean get() = dslObservable
    override val notificationType: CoapMessageType get() = dslNotificationType
    override val recognizedOptions: Set<Int> get() = dslRecognized

    override suspend fun handle(context: RequestContext): CoapResponse {
        val handler = handlers[context.method]
            ?: return CoapResponse(if (handlers.isEmpty()) CoapCode.NOT_FOUND else CoapCode.METHOD_NOT_ALLOWED)
        return context.handler()
    }
}

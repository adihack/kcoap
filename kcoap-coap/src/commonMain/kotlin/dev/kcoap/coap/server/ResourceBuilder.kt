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

    public fun get(handler: RequestHandlerFunction): Unit = on(CoapCode.GET, handler)
    public fun post(handler: RequestHandlerFunction): Unit = on(CoapCode.POST, handler)
    public fun put(handler: RequestHandlerFunction): Unit = on(CoapCode.PUT, handler)
    public fun delete(handler: RequestHandlerFunction): Unit = on(CoapCode.DELETE, handler)
    public fun fetch(handler: RequestHandlerFunction): Unit = on(CoapCode.FETCH, handler)
    public fun patch(handler: RequestHandlerFunction): Unit = on(CoapCode.PATCH, handler)
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

    override val attributes: ResourceAttributes get() = dslAttributes
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

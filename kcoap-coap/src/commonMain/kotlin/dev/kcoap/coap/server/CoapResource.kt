package dev.kcoap.coap.server

import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapResponse
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A resource of a [CoapServer], identified by its path from the root. Subclass it and override the
 * handlers for the methods it supports; the others answer 4.05 Method Not Allowed (RFC 7252 §5.9.2.6).
 * For resources defined inline, see the DSL in [ResourceBuilder].
 *
 * ```
 * class Temperature : CoapResource("temperature") {
 *     override val attributes = ResourceAttributes(resourceTypes = listOf("temperature-c"))
 *     override suspend fun handleGet(context: RequestContext) = CoapResponse.content("22.5")
 * }
 * ```
 *
 * Handlers run concurrently on the server's coroutines, one per request, so a resource must be thread
 * safe. A handler that throws produces 5.00 Internal Server Error, and so does a failure anywhere else in
 * the server's pipeline, the security layer and the application callbacks behind it included (see
 * [CoapServer]); a failing notification removes its observer. Children can be added and removed at any
 * time, for example to create resources on PUT or POST.
 *
 * @property name the Uri-Path segment of this resource; the root has the empty name.
 */
@OptIn(ExperimentalAtomicApi::class)
public abstract class CoapResource(public val name: String) {
    init {
        require('/' !in name) { "A resource name is one path segment: '$name'" }
    }

    private val childMap = AtomicReference<Map<String, CoapResource>>(emptyMap())
    private val parentRef = AtomicReference<CoapResource?>(null)

    /** Set on the root of a server's tree only. */
    @Volatile
    internal var server: CoapServer? = null

    /** Link attributes for `/.well-known/core`. */
    public open val attributes: ResourceAttributes get() = ResourceAttributes.NONE

    /** Whether `/.well-known/core` lists this resource (RFC 6690 §4: which links to include is up to the server). */
    public open val isDiscoverable: Boolean get() = true

    /**
     * Who may use this resource and, unless a child has a policy of its own, everything below it (ADR 0008).
     * Null inherits the parent's, and the root's falls back to [CoapServerConfig.defaultAccess]; see
     * [AccessPolicy] for when the server asks it.
     */
    public open val access: AccessPolicy? get() = null

    /** Whether GET or FETCH with Observe registers observers (RFC 7641 §4.1); see [ObservableResource]. */
    public open val isObservable: Boolean get() = false

    /**
     * Critical options this resource processes itself, beyond those the server implements (RFC 7252
     * §5.4.1). A request with any other unrecognized critical option gets 4.02 Bad Option before a handler
     * runs. For example, a resource that honours conditional requests adds If-Match and If-None-Match
     * (see [RequestContext.preconditionsHold]).
     */
    public open val recognizedOptions: Set<Int> get() = emptySet()

    /** The parent resource, or null for a root or a detached resource. */
    public val parent: CoapResource? get() = parentRef.load()

    /** The child resources, in the order they were added. */
    public val children: Collection<CoapResource> get() = childMap.load().values

    /** "/a/b", or "/" for the root. */
    public val path: String get() = segments.joinToString("/", prefix = "/")

    /** The Uri-Path segments from the root to this resource; empty for the root. */
    internal val segments: List<String>
        get() {
            val segments = ArrayDeque<String>()
            var current: CoapResource? = this
            while (current?.parent != null) {
                segments.addFirst(current.name)
                current = current.parent
            }
            return segments
        }

    /** The child called [name], or null. */
    public fun child(name: String): CoapResource? = childMap.load()[name]

    /**
     * Adds [child] under this resource and returns it.
     *
     * @throws IllegalArgumentException if a child of that name exists or [child] has a parent.
     */
    public fun add(child: CoapResource): CoapResource {
        require(child.name.isNotEmpty()) { "Only the root has an empty name" }
        require(child.parentRef.compareAndSet(null, this)) { "${child.name} already has a parent" }
        while (true) {
            val current = childMap.load()
            if (child.name in current) {
                child.parentRef.store(null)
                throw IllegalArgumentException("$path already has a child named ${child.name}")
            }
            if (childMap.compareAndSet(current, current + (child.name to child))) return child
        }
    }

    /**
     * Removes and returns the child called [name]. Its observers, and those of its children, get a 4.04
     * notification and are removed (RFC 7641 §3.2).
     */
    public fun remove(name: String): CoapResource? {
        while (true) {
            val current = childMap.load()
            val child = current[name] ?: return null
            if (childMap.compareAndSet(current, current - name)) {
                val server = root.server
                child.parentRef.store(null)
                child.onRemoved(server)
                return child
            }
        }
    }

    /** The resource at [segments] below this one, or null. */
    public fun find(segments: List<String>): CoapResource? {
        var current: CoapResource = this
        for (segment in segments) current = current.child(segment) ?: return null
        return current
    }

    /** The root of the tree this resource belongs to. */
    internal val root: CoapResource
        get() {
            var current = this
            while (true) current = current.parent ?: return current
        }

    internal open fun onRemoved(server: CoapServer?) {
        children.forEach { it.onRemoved(server) }
    }

    /**
     * Dispatches to the handler for the request method (template method). Override it to handle methods
     * generically, for example to proxy.
     */
    public open suspend fun handle(context: RequestContext): CoapResponse = when (context.method) {
        CoapCode.GET -> handleGet(context)
        CoapCode.POST -> handlePost(context)
        CoapCode.PUT -> handlePut(context)
        CoapCode.DELETE -> handleDelete(context)
        CoapCode.FETCH -> handleFetch(context)
        CoapCode.PATCH -> handlePatch(context)
        CoapCode.IPATCH -> handleIPatch(context)
        else -> CoapResponse(CoapCode.METHOD_NOT_ALLOWED)
    }

    /** GET (RFC 7252 §5.8.1); also produces every Observe notification of an observable resource. */
    public open suspend fun handleGet(context: RequestContext): CoapResponse = methodNotAllowed()

    /** POST (RFC 7252 §5.8.2). */
    public open suspend fun handlePost(context: RequestContext): CoapResponse = methodNotAllowed()

    /** PUT (RFC 7252 §5.8.3). */
    public open suspend fun handlePut(context: RequestContext): CoapResponse = methodNotAllowed()

    /** DELETE (RFC 7252 §5.8.4). */
    public open suspend fun handleDelete(context: RequestContext): CoapResponse = methodNotAllowed()

    /** FETCH (RFC 8132 §2); observable like GET (RFC 8132 §2.4). */
    public open suspend fun handleFetch(context: RequestContext): CoapResponse = methodNotAllowed()

    /** PATCH (RFC 8132 §3). */
    public open suspend fun handlePatch(context: RequestContext): CoapResponse = methodNotAllowed()

    /** iPATCH (RFC 8132 §3). */
    public open suspend fun handleIPatch(context: RequestContext): CoapResponse = methodNotAllowed()

    protected fun methodNotAllowed(): CoapResponse = CoapResponse(CoapCode.METHOD_NOT_ALLOWED)

    override fun toString(): String = "${this::class.simpleName ?: "CoapResource"}($path)"
}

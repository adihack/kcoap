package dev.kcoap.coap.server

import dev.kcoap.coap.BlockOption
import dev.kcoap.coap.CoapCode
import dev.kcoap.coap.CoapMessage
import dev.kcoap.coap.CoapMessageType
import dev.kcoap.coap.CoapOptions
import dev.kcoap.coap.CoapResponse
import dev.kcoap.coap.OptionSet
import dev.kcoap.coap.security.MessageSecurityException
import dev.kcoap.coap.security.UnprotectedRequest
import dev.kcoap.coap.stack.Blockwise
import dev.kcoap.coap.stack.CoapEndpoint
import dev.kcoap.coap.stack.CoapEndpointConfig
import dev.kcoap.coap.stack.InboundRequest
import dev.kcoap.coap.stack.OperationKey
import dev.kcoap.coap.stack.RequestHandler
import dev.kcoap.log.LogTags
import dev.kcoap.transport.ConnectionListener
import dev.kcoap.transport.DatagramTransport
import dev.kcoap.transport.Endpoint
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * A CoAP server: routes requests on a [CoapEndpoint] to a tree of [CoapResource]s.
 *
 * For each request, in the order of RFC 8613 Figure 1:
 * 1. message security verifies and decrypts it ([CoapServerConfig.security]); a failure is answered
 *    unprotected with Max-Age 0 (RFC 8613 §8.2), a freshness challenge replaces the response;
 * 2. Proxy-Uri and Proxy-Scheme get 5.05 (RFC 7252 §5.10.2), unknown paths 4.04, and unrecognized
 *    critical options 4.02 with a diagnostic, or a silent rejection for Non-confirmable requests
 *    (RFC 7252 §5.4.1);
 * 3. Block1 bodies are assembled (RFC 7959 §2.5) and Observe registrations recorded (RFC 7641 §4.1);
 * 4. the resource's handler runs; large responses go out in Block2 blocks (RFC 7959 §2.4);
 * 5. the response is protected with the request's security context and sent, piggybacked when it is
 *    ready in time, otherwise separately (RFC 7252 §5.2). No-Response (RFC 7967) suppresses it.
 *
 * Block-wise state is kept per peer [Endpoint] and per message-security identity, compared with
 * `equals`, so blocks of different security contexts never mix: a [CoapServerConfig.security]
 * implementation should return equal [dev.kcoap.coap.security.SecurityIdentity] objects for requests of
 * one context, otherwise its Block1 transfers end in 4.08.
 *
 * ```
 * val server = CoapServer(KtorUdpTransport.bind(port = 5683), scope) {
 *     resource("hello") { get { CoapResponse.content("Hello, ${peer}") } }
 * }
 * ```
 */
public class CoapServer private constructor(
    public val endpoint: CoapEndpoint,
    public val config: CoapServerConfig,
    private val ownsEndpoint: Boolean,
    routes: ResourceBuilder.() -> Unit,
) {
    /** Serves on an existing [endpoint], which a [dev.kcoap.coap.client.CoapClient] may share. */
    public constructor(
        endpoint: CoapEndpoint,
        config: CoapServerConfig = CoapServerConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(endpoint, config, false, routes)

    /** Serves on a new endpoint over [transport]; [close] closes both. */
    public constructor(
        transport: DatagramTransport,
        scope: CoroutineScope,
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(CoapEndpoint(transport, scope, endpointConfig), config, true, routes)

    /**
     * Serves CoAP over reliable transports (RFC 8323) on the connections [listeners] accept, such as TCP
     * and WebSocket listeners, on a new endpoint; [close] closes it and the listeners.
     */
    public constructor(
        listeners: List<ConnectionListener>,
        scope: CoroutineScope,
        config: CoapServerConfig = CoapServerConfig(),
        endpointConfig: CoapEndpointConfig = CoapEndpointConfig(),
        routes: ResourceBuilder.() -> Unit = {},
    ) : this(CoapEndpoint(scope, endpointConfig, listeners = listeners), config, true, routes)

    private val log = endpoint.config.loggerFactory.logger(LogTags.COAP_SERVER)
    private val rootResource = DslResource("")
    internal val observations = ObservationRegistry(this)
    private val blockwise = BlockwiseServer(config, endpoint.parameters.exchangeLifetime)
    private val reserve = if (config.security != null) config.securityReserve else 0

    /** The parent of this server's request coroutines, so [close] stops them on a shared endpoint too. */
    private val handlers = SupervisorJob(endpoint.scope.coroutineContext[Job])

    /** The root of the resource tree; requests to "/" go to its handlers. */
    public val root: CoapResource get() = rootResource

    init {
        rootResource.server = this
        ResourceBuilder(rootResource).routes()
        if (config.wellKnownCore) {
            val container = rootResource.child(WellKnownCore.CONTAINER) ?: rootResource.add(DslResource(WellKnownCore.CONTAINER))
            // A /.well-known/core the application defined itself takes precedence.
            if (container.child(WellKnownCore.NAME) == null) container.add(WellKnownCore(rootResource))
        }
        check(endpoint.requestHandler == null) { "$endpoint already has a server" }
        endpoint.requestHandler = object : RequestHandler {
            override val context: CoroutineContext get() = handlers

            override suspend fun handle(request: InboundRequest) = process(request)

            override suspend fun onSessionEnded(peer: Endpoint) {
                observations.sessionEnded(peer)
                blockwise.forget(peer)
            }

            override suspend fun onEndpointClosed() {
                stop()
            }
        }
        // Only now, with the resources and the handler in place, does the endpoint start receiving.
        endpoint.attach()
        log.info { "Server started on $endpoint" }
    }

    /** Adds [resource] below the root. */
    public fun add(resource: CoapResource): CoapResource = rootResource.add(resource)

    /**
     * Stops serving: requests from now on are rejected as if there were no server, the handlers still
     * running are cancelled, and observers are dropped silently. Closes the endpoint too if the server
     * created it. A server also stops when its endpoint closes.
     */
    public suspend fun close() {
        endpoint.requestHandler = null
        stop()
        if (ownsEndpoint) endpoint.close()
        log.info { "Server stopped" }
    }

    /** Cancels the handlers, not joined (one of them may be the caller), and takes no observers any more. */
    private suspend fun stop() {
        handlers.cancel()
        observations.close()
    }

    // ---------------------------------------------------------------- the request pipeline

    private class Reply(val response: CoapResponse, val observation: ServerObservation? = null)

    private suspend fun process(inbound: InboundRequest) {
        val security = config.security
        val unprotected = if (security == null) {
            null
        } else {
            try {
                security.unprotectRequest(inbound.message, inbound.peer)
            } catch (e: MessageSecurityException) {
                log.warn { "Rejected a protected request from ${inbound.peer}: ${e.message}" }
                inbound.respond(securityError(e).toMessage(inbound.message.token))
                return
            }
        }
        val request = unprotected?.message ?: inbound.message
        val challenge = unprotected?.challenge
        if (unprotected != null && challenge != null) {
            // RFC 8613 Appendix B.1.2: the security layer answers instead of the resource.
            log.debug { "Security challenge ${challenge.code} for ${inbound.peer}" }
            inbound.respond(unprotected.protectResponse(challenge.toBuilder().apply { token = request.token }.build()))
            return
        }
        val reply = try {
            answer(inbound, request, unprotected) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.error(e) { "Failed to answer ${request.code} ${request.options.path}" }
            Reply(CoapResponse(CoapCode.INTERNAL_SERVER_ERROR))
        }
        send(inbound, request, unprotected, reply)
    }

    /**
     * RFC 8613 §8.2: a security error is answered unprotected, with Outer Max-Age 0, carrying either the
     * security layer's structured error body (such as an EDHOC error message, RFC 9668 §3.3.1) in its
     * Content-Format, or the diagnostic text (RFC 7252 §5.5.2).
     */
    private fun securityError(e: MessageSecurityException): CoapResponse {
        val body = e.errorPayload
        val options = OptionSet.build {
            if (body != null) e.contentFormat?.let { contentFormat(it) }
            maxAge(0)
        }
        return CoapResponse(e.responseCode, options, body ?: e.diagnostic?.encodeToByteArray() ?: ByteArray(0))
    }

    /** The reply to [request], or null when the request is rejected without one. */
    private suspend fun answer(inbound: InboundRequest, request: CoapMessage, unprotected: UnprotectedRequest?): Reply? {
        val options = request.options
        val peer = inbound.peer
        val resource = rootResource.find(options.uriPath)
        val unrecognized = OptionCheck.unrecognizedCritical(options, OptionCheck.SERVER_RECOGNIZED + (resource?.recognizedOptions ?: emptySet()))
        if (unrecognized.isNotEmpty()) {
            val numbers = unrecognized.joinToString { it.number.toString() }
            if (!inbound.isConfirmable) {
                // RFC 7252 §5.4.1: in a Non-confirmable message they cause a rejection.
                inbound.reject("unrecognized critical options $numbers")
                return null
            }
            return Reply(CoapResponse.error(CoapCode.BAD_OPTION, "Unrecognized critical option $numbers"))
        }
        if (options.proxyUri != null || options.proxyScheme != null) {
            return Reply(CoapResponse.error(CoapCode.PROXYING_NOT_SUPPORTED, "This server is not a forward-proxy"))
        }
        if (resource == null) return Reply(CoapResponse(CoapCode.NOT_FOUND))
        val block1 = options.block1
        val block2 = options.block2
        val limits = endpoint.limits(inbound.connection)
        if (!limits.isReliable && (block1?.isBert == true || block2?.isBert == true)) {
            // RFC 7959 §2.2: SZX 7 is reserved and MUST lead to 4.00; it is BERT on RFC 8323 transports only.
            return Reply(CoapResponse.error(CoapCode.BAD_REQUEST, "Block size exponent 7 is reserved"))
        }
        val now = endpoint.now()
        val key = OperationKey(peer, unprotected?.identity, request.code.value, Blockwise.operationOptions(options))

        var effective = request
        var block1Control: BlockOption? = null
        if (block1 != null) {
            when (val result = blockwise.receiveBlock1(key, request, block1, now, limits)) {
                is BlockwiseServer.Block1Result.Respond -> return Reply(result.response)
                is BlockwiseServer.Block1Result.Assembled -> {
                    effective = result.request
                    block1Control = result.control
                }
            }
        } else if (request.payloadSize > config.maxBodySize) {
            return Reply(CoapResponse.error(CoapCode.REQUEST_ENTITY_TOO_LARGE).withOptions { size1(config.maxBodySize.toLong()) })
        }

        if (block2 != null && block2.number > 0 && block1 == null) {
            blockwise.representation(key, now)?.let { cached ->
                return Reply(blockwise.serve(key, request.token, block2, cached, reserve, now, limits))
            }
            if (request.code != CoapCode.GET && request.code != CoapCode.FETCH) {
                // Re-running an unsafe method for a later block would repeat its effect.
                return Reply(CoapResponse.error(CoapCode.REQUEST_ENTITY_INCOMPLETE, "No response body to continue"))
            }
        }

        val observable = resource as? ObservableResource
        val observe = options.observe
        val isObserveMethod = request.code == CoapCode.GET || request.code == CoapCode.FETCH
        if (observe == OBSERVE_DEREGISTER && isObserveMethod) {
            observations.remove(peer, request.token, "deregistered")
        }
        val registering = observe == OBSERVE_REGISTER && isObserveMethod && observable != null &&
            observable.isObservable && (block2 == null || block2.number == 0L)

        val context = RequestContext(effective, peer, unprotected?.identity, resource, inbound, inbound.peerIdentity)
        var response = acceptable(options, invoke(resource, context))
        var observation: ServerObservation? = null
        if (registering && response.isSuccess) {
            observation = ServerObservation(
                this, observable, peer, request.token, effective, unprotected, unprotected?.identity,
                block2?.size, key, now, inbound.connection, inbound.peerIdentity,
            )
            if (observations.register(observation)) {
                response = response.withOptions { observe(observable.nextSequence()) }
            } else {
                // The server stopped meanwhile; RFC 7641 §4.1 lets it answer as to a plain GET.
                observation = null
                response = response.withOptions { remove(CoapOptions.OBSERVE.number) }
            }
        } else if (CoapOptions.OBSERVE.number in response.options) {
            // RFC 7641 §4.1: only a registered observer gets a response with Observe.
            response = response.withOptions { remove(CoapOptions.OBSERVE.number) }
        }
        block1Control?.let { control -> response = response.withOptions { block1(control) } }
        return Reply(blockwise.serve(key, request.token, block2, response, reserve, now, limits), observation)
    }

    /**
     * RFC 7252 §5.10.4: a successful representation in another Content-Format than the one the client
     * accepts becomes 4.06 Not Acceptable. Error responses take precedence, and responses without a
     * Content-Format say nothing about their format.
     */
    private fun acceptable(options: OptionSet, response: CoapResponse): CoapResponse {
        val accept = options.accept ?: return response
        val format = response.contentFormat
        if (!response.isSuccess || format == null || format == accept) return response
        return CoapResponse.error(CoapCode.NOT_ACCEPTABLE)
    }

    private suspend fun invoke(resource: CoapResource, context: RequestContext): CoapResponse = try {
        resource.handle(context)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.error(e) { "Handler of ${resource.path} failed on ${context.method}" }
        CoapResponse(CoapCode.INTERNAL_SERVER_ERROR)
    }

    private suspend fun send(inbound: InboundRequest, request: CoapMessage, unprotected: UnprotectedRequest?, reply: Reply) {
        val response = reply.response
        if (reply.observation == null && isSuppressed(request.options, response.code)) {
            // RFC 7967 §2: the client is not interested; a Confirmable request still gets its ACK.
            inbound.finishWithoutResponse()
            return
        }
        val message = response.toMessage(request.token)
        val outgoing = unprotected?.protectResponse(message) ?: message
        log.debug { "${response.code} for ${request.code} ${request.options.path} from ${inbound.peer}" }
        val observation = reply.observation
        val transmission = try {
            inbound.respond(outgoing, owner = observation)
        } catch (e: Exception) {
            observation?.let { observations.remove(it, "registration response failed") }
            throw e
        }
        observation?.onRegistrationSent(transmission, transmission?.message?.type ?: CoapMessageType.ACK, endpoint.now())
    }

    /** RFC 7967 §2.1: bit 2 suppresses 2.xx, bit 8 4.xx and bit 16 5.xx responses. */
    private fun isSuppressed(options: OptionSet, code: CoapCode): Boolean {
        val value = options.noResponse ?: return false
        val mask = when (code.codeClass) {
            2 -> 0x02
            4 -> 0x08
            5 -> 0x10
            else -> 0
        }
        return value and mask != 0
    }

    // ---------------------------------------------------------------- notifications

    /**
     * A notification for [observation] with the resource's current state (RFC 7641 §4.2): the handler's
     * 2.xx response with a new Observe value, the first block if it is large (RFC 7959 §2.6), protected
     * with the registration's context. A non-2.xx response ends the observation and goes out without
     * Observe.
     */
    internal suspend fun notification(observation: ServerObservation): CoapMessage? {
        if (!observation.isActive) return null
        val resource = observation.resource
        val context = RequestContext(
            observation.request, observation.peer, observation.identity, resource, inbound = null, observation.peerIdentity,
        )
        var response = invoke(resource, context)
        response = if (response.isSuccess) {
            response.withOptions { observe(resource.nextSequence()) }
        } else {
            observations.remove(observation, "non-2.xx notification ${response.code}")
            response.withOptions { remove(CoapOptions.OBSERVE.number) }
        }
        val requested = observation.blockSize?.let { Blockwise.block(0, false, it) }
        val limits = endpoint.limits(observation.connection)
        response = blockwise.serve(observation.operationKey, observation.token, requested, response, reserve, endpoint.now(), limits)
        val message = response.toMessage(observation.token)
        return observation.unprotected?.protectResponse(message) ?: message
    }

    /** RFC 7641 §3.2: the resource is gone; 4.04 without Observe ends the observation. */
    internal suspend fun sendRemovalNotice(observation: ServerObservation) {
        val message = CoapResponse(CoapCode.NOT_FOUND).toMessage(observation.token)
        val outgoing = observation.unprotected?.protectResponse(message) ?: message
        endpoint.sendSeparately(observation.peer, outgoing, CoapMessageType.CON, observation, connection = observation.connection)
    }

    override fun toString(): String = "CoapServer($endpoint)"

    private companion object {
        const val OBSERVE_REGISTER = 0L
        const val OBSERVE_DEREGISTER = 1L
    }
}
